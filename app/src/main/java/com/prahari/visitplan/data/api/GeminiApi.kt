package com.prahari.visitplan.data.api

import com.prahari.visitplan.data.models.CenterData
import com.prahari.visitplan.data.models.DayPlan
import com.prahari.visitplan.data.secure.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gemini API — Visit Plan ko optimize karne ke liye
 * Plan banane ke baad Gemini se review karwata hai:
 * - Kya staff diversity sahi hai?
 * - Kya high-score centers sahi din pe hain?
 * - Koi behtar arrangement hai?
 */
class GeminiApi(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun apiKey(): String =
        secureStorage.getGeminiKey() ?: throw IllegalStateException("Gemini API key set nahi hai")

    private suspend fun generateContent(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("contents", buildJsonArray {
                    addJsonObject {
                        put("parts", buildJsonArray {
                            addJsonObject { put("text", prompt) }
                        })
                    }
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.3)
                })
            }.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${apiKey()}")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Gemini error ${resp.code}: ${text.take(200)}"))
                }
                val obj = json.parseToJsonElement(text).jsonObject
                val content = obj["candidates"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: return@withContext Result.failure(Exception("Gemini se jawab nahi mila"))
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Plan ko Gemini se optimize karwao.
     * Input: current plan + saare centers ka data
     * Output: behtar plan (JSON) + notes (Hindi/Hinglish)
     */
    suspend fun optimizePlan(
        days: List<DayPlan>,
        allCenters: List<CenterData>,
        auditName: String,
        minTarget: Int
    ): Result<PlanOptimization> {
        val daysText = days.joinToString("\n") { day ->
            "${day.dayName} (${day.date}) - Staff: ${day.staffName}\n" +
            day.centers.joinToString("\n") { c ->
                "  - ${c.center.centerName} (${c.center.centerCode}) | Members: ${c.memberCount} | New: ${c.newMemberCount} | Score: ${c.score}"
            } +
            "\n  Total: ${day.centers.size} centers, ${day.totalMembers} members, ${day.totalNewMembers} new, Score: ${day.totalScore}"
        }

        val prompt = """$PLAN_PROMPT

Audit: $auditName
Minimum target: $minTarget centers

Current Plan:
$daysText

Total centers in plan: ${days.sumOf { it.centers.size }}
Total staff covered: ${days.map { it.staffName }.distinct().size}

Return JSON only:
{
  "notes": "Hindi/Hinglish me 3-4 line me plan ka review: kya achha hai, kya sudhaar sakte hain",
  "suggestions": ["suggestion 1", "suggestion 2"],
  "is_good": true/false
}"""

        val r = generateContent(prompt)
        return r.mapCatching { text ->
            val cleaned = text.replace("```json", "").replace("```", "").trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            val jsonStr = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else "{}"
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            PlanOptimization(
                notes = obj["notes"]?.jsonPrimitive?.contentOrNull ?: "",
                suggestions = obj["suggestions"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList(),
                isGood = obj["is_good"]?.jsonPrimitive?.booleanOrNull ?: true
            )
        }
    }

    data class PlanOptimization(
        val notes: String,
        val suggestions: List<String>,
        val isGood: Boolean
    )

    companion object {
        const val MODEL = "gemini-1.5-flash"

        const val PLAN_PROMPT = """You are a field audit visit plan optimizer for Sonata Microfinance (India).

Rules for the plan:
1. ONE staff per day only — ek din me sirf ek staff ke centers visit karo
2. Centers ONLY Monday to Friday — Saturday ko koi center meeting nahi hoti
3. Daily center count FLEXIBLE — kuch din 4, kuch din 6-7 centers ho sakte hain
4. Staff diversity — 5 visit days me kam se kam 4 ALAG staff cover hone chahiye
5. Score per center = (New Members x 2) + Members — high score centers ko priority do
6. New member = jiska disbursement pichhle mahine (previous month) me hua ho
7. Har center apne meeting day par hi visit hona chahiye (agar meeting day pata ho)

Review the plan below. Check:
- Kya har din sirf ek staff hai?
- Kya 5 din me kam se kam 4 alag staff hain?
- Kya high-score centers miss to nahi ho rahe?
- Kya daily count balanced hai (koi din bahut zyada/kam to nahi)?

Be honest and practical. Agar plan achha hai to batao, agar sudhaar chahiye to specific suggestions do."""
    }
}
