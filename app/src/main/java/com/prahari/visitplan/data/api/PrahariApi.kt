package com.prahari.visitplan.data.api

import com.prahari.visitplan.data.models.Audit
import com.prahari.visitplan.data.models.Center
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
 * Prahari API Client — Visit Plan ke liye
 * Saari requests phone se direct (InstaSafe VPN on hona chahiye)
 * Base: https://prahari.sonataindia.com
 */
class PrahariApi(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- Login ----------
    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("username", username)
                put("password", password)
            }.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/backend/auth/login/")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Login failed: ${resp.code}"))
                val obj = json.parseToJsonElement(text).jsonObject
                val token = obj["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: obj["token"]?.jsonPrimitive?.contentOrNull
                    ?: obj["access"]?.jsonPrimitive?.contentOrNull
                    ?: obj["jwt"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext Result.failure(Exception(obj["message"]?.jsonPrimitive?.contentOrNull ?: "Login failed"))
                secureStorage.savePrahariToken(token)
                secureStorage.savePrahariUsername(username)
                Result.success(token)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- Generic POST ----------
    suspend fun post(endpoint: String, payload: Map<String, Any?> = emptyMap()): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val token = secureStorage.getPrahariToken()
                    ?: return@withContext Result.failure(Exception("Prahari me login nahi ho"))
                val bodyJson = buildJsonObject {
                    put("token", token)
                    for ((k, v) in payload) {
                        when (v) {
                            null -> put(k, JsonNull)
                            is String -> put(k, v)
                            is Number -> put(k, v.toDouble())
                            is Boolean -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$BASE_URL$endpoint")
                    .post(bodyJson)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@withContext Result.failure(Exception("API Error: ${resp.code}"))
                    Result.success(json.parseToJsonElement(text).jsonObject)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---------- Assigned audits ----------
    suspend fun getAudits(): Result<List<Audit>> {
        var r = post("/backend/audit/assigned-audits/")
        if (r.isFailure) {
            r = post("/backend/audit/list/")
        }
        return r.map { obj ->
            val keys = obj.keys.joinToString(",")
            val arr = obj["audits"]?.jsonArray
                ?: obj["data"]?.jsonArray
                ?: obj["results"]?.jsonArray
                ?: obj["assigned_audits"]?.jsonArray
                ?: obj.values.firstOrNull {
                    try { it.jsonArray.isNotEmpty() } catch (_: Exception) { false }
                }?.jsonArray
                ?: emptyList()
            val audits = arr.mapNotNull { el ->
                try {
                    val o = el.jsonObject
                    fun str(vararg keys: String): String {
                        for (k in keys) {
                            o[k]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) return it }
                            o[k]?.jsonPrimitive?.intOrNull?.let { return it.toString() }
                        }
                        return ""
                    }
                    val id = str("id", "audit_id", "auditId", "auditID")
                    if (id.isEmpty()) return@mapNotNull null
                    Audit(
                        id = id,
                        name = str("name", "audit_name", "auditName", "title", "branch_name"),
                        branchId = str("branch_id", "branchId", "branchID"),
                        branchName = str("branch_name", "branchName"),
                        startDate = str("start_date", "startDate", "from_date"),
                        endDate = str("end_date", "endDate", "to_date")
                    )
                } catch (_: Exception) { null }
            }
            if (audits.isEmpty()) {
                throw Exception("Audit parse nahi hua. Response keys: $keys")
            }
            audits
        }
    }

    // ---------- Centers via risk-details (branch_name se) ----------
    suspend fun getCentersByBranch(branchName: String): Result<List<Center>> {
        val r = post("/backend/audit/centers/risk-details/", mapOf("branch_name" to branchName))
        return r.map { obj ->
            val arr = obj["center_risks"]?.jsonArray
                ?: obj["centers"]?.jsonArray
                ?: obj["data"]?.jsonArray
                ?: obj["results"]?.jsonArray
                ?: emptyList()
            arr.mapNotNull { el ->
                try {
                    val o = el.jsonObject
                    fun str(vararg keys: String): String {
                        for (k in keys) {
                            o[k]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) return it }
                            o[k]?.jsonPrimitive?.intOrNull?.let { return it.toString() }
                        }
                        return ""
                    }
                    Center(
                        centerId = str("CenterID", "center_id", "centerId", "id"),
                        centerCode = str("Centercode", "center_code", "centercode"),
                        centerName = str("CenterName", "center_name", "centername"),
                        meetingDay = str("CenterMeetingDay", "meeting_day", "meetingDay"),
                        meetingTime = str("CenterMeetingTime", "meeting_time", "meetingTime"),
                        staffName = str("StaffName", "staff_name", "staffName", "BROName", "bro_name"),
                        staffId = str("StaffID", "staff_id", "staffId", "EmployeeID"),
                        branchName = branchName
                    )
                } catch (_: Exception) { null }
            }
        }
    }

    // ---------- Selected centers for audit ----------
    suspend fun getSelectedCenters(auditId: String): Result<List<Center>> {
        val r = post("/backend/audit/selected-centers/", mapOf("audit_id" to auditId))
        return r.map { obj ->
            val arr = obj["centers"]?.jsonArray
                ?: obj["data"]?.jsonArray
                ?: obj["results"]?.jsonArray
                ?: emptyList()
            arr.mapNotNull { el ->
                try {
                    val o = el.jsonObject
                    fun str(vararg keys: String): String {
                        for (k in keys) {
                            o[k]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) return it }
                            o[k]?.jsonPrimitive?.intOrNull?.let { return it.toString() }
                        }
                        return ""
                    }
                    Center(
                        centerId = str("center_id", "CenterID", "id"),
                        centerCode = str("center_code", "Centercode", "centercode"),
                        centerName = str("center_name", "CenterName", "centername"),
                        meetingDay = str("meeting_day", "CenterMeetingDay"),
                        meetingTime = str("meeting_time", "CenterMeetingTime"),
                        staffName = str("staff_name", "StaffName"),
                        staffId = str("staff_id", "StaffID"),
                        branchName = ""
                    )
                } catch (_: Exception) { null }
            }
        }
    }

    // ---------- Members for a center ----------
    suspend fun getMembers(centerId: String, asOnDate: String): Result<JsonArray> {
        val r = post("/backend/audit/customers/risk-details/", mapOf("center_id" to centerId, "as_on_date" to asOnDate))
        return r.map { obj ->
            obj["customer_risks"]?.jsonArray
                ?: obj["customers"]?.jsonArray
                ?: emptyList()
        }
    }

    // ---------- Disbursements for a center ----------
    suspend fun getDisbursements(centerId: String, asOnDate: String): Result<JsonObject> =
        post("/backend/audit/centers/disbursements/", mapOf("center_id" to centerId, "as_on_date" to asOnDate))

    companion object {
        const val BASE_URL = "https://prahari.sonataindia.com"
    }
}
