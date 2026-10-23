package com.prahari.visitplan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prahari.visitplan.data.api.GeminiApi
import com.prahari.visitplan.data.api.PrahariApi
import com.prahari.visitplan.data.models.Audit
import com.prahari.visitplan.data.models.CenterData
import com.prahari.visitplan.data.models.VisitPlan
import com.prahari.visitplan.data.planner.PlanGenerator
import com.prahari.visitplan.data.secure.SecureStorage
import com.prahari.visitplan.ui.screens.AuditSelectScreen
import com.prahari.visitplan.ui.screens.LoginScreen
import com.prahari.visitplan.ui.screens.PlanDisplayScreen
import com.prahari.visitplan.ui.theme.VisitPlanTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secureStorage = SecureStorage(this)
        val prahariApi = PrahariApi(secureStorage)
        val geminiApi = GeminiApi(secureStorage)

        setContent {
            VisitPlanTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VisitPlanApp(prahariApi, geminiApi, secureStorage)
                }
            }
        }
    }
}

@Composable
fun VisitPlanApp(
    prahariApi: PrahariApi,
    geminiApi: GeminiApi,
    secureStorage: SecureStorage
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("login") } // login, audits, loading, plan
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var audits by remember { mutableStateOf<List<Audit>>(emptyList()) }
    var plan by remember { mutableStateOf<VisitPlan?>(null) }
    var loadingMsg by remember { mutableStateOf("") }
    var geminiNotes by remember { mutableStateOf("") }
    var geminiSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isOptimizing by remember { mutableStateOf(false) }

    fun doLogin(username: String, password: String, geminiKey: String) {
        scope.launch {
            isLoading = true
            error = null
            if (geminiKey.isNotBlank()) {
                secureStorage.saveGeminiKey(geminiKey)
            }
            val r = prahariApi.login(username, password)
            isLoading = false
            if (r.isSuccess) {
                screen = "audits"
                isLoading = true
                val ar = prahariApi.getAudits()
                isLoading = false
                if (ar.isSuccess) {
                    audits = ar.getOrNull() ?: emptyList()
                } else {
                    error = ar.exceptionOrNull()?.message ?: "Audits nahi mile"
                }
            } else {
                error = r.exceptionOrNull()?.message ?: "Login failed"
            }
        }
    }

    fun generatePlan(audit: Audit) {
        scope.launch {
            screen = "loading"
            error = null
            try {
                // 1. Centers nikalo
                loadingMsg = "Centers load ho rahe hain..."
                var centers = prahariApi.getSelectedCenters(audit.id).getOrNull()
                if (centers.isNullOrEmpty()) {
                    loadingMsg = "Branch se centers load ho rahe hain..."
                    centers = prahariApi.getCentersByBranch(audit.branchName).getOrNull()
                }
                if (centers.isNullOrEmpty()) {
                    error = "Koi center nahi mila"
                    screen = "audits"
                    return@launch
                }

                // 2. Har center ka data: members + new members
                // PARALLEL FETCH: saare centers ek saath (10-20x faster!)
                // Pehle 1-1 karke fetch hota tha: 100 centers x 2 calls x 2 sec = ~7 min
                // Ab parallel: saare ek saath = ~30 sec max
                val asOnDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

                // Audit month se previous month nikalo
                val auditCal = Calendar.getInstance()
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    auditCal.time = sdf.parse(audit.startDate) ?: Calendar.getInstance().time
                } catch (_: Exception) {}
                val auditMonth = auditCal.get(Calendar.MONTH) + 1
                val auditYear = auditCal.get(Calendar.YEAR)
                val (prevMonth, prevYear) = PlanGenerator.getPreviousMonth(auditMonth, auditYear)

                loadingMsg = "Saare ${centers.size} centers ka data ek saath fetch ho raha hai..."
                val centerDataList = mutableListOf<CenterData>()
                var completedCount = 0

                // Parallel: har center ke liye ek async task
                // Batch size 20: ek saath 20 centers, phir agle 20 (server overload na ho)
                val batchSize = 20
                for (batch in centers.chunked(batchSize)) {
                    val deferredList = batch.map { center ->
                        async {
                            // Members + Disbursements ek saath (dono parallel)
                            val membersDeferred = async { prahariApi.getMembers(center.centerId, asOnDate).getOrNull() }
                            val disbDeferred = async { prahariApi.getDisbursements(center.centerId, asOnDate).getOrNull() }
                            val members = membersDeferred.await()
                            val disb = disbDeferred.await()
                            val memberCount = members?.size ?: 0

                            // New members: disbursement date previous month me ho
                            var newCount = 0
                            if (disb != null) {
                                val arr = disb["disbursements"]?.jsonArray
                                    ?: disb["data"]?.jsonArray
                                    ?: disb["results"]?.jsonArray
                                if (arr != null) {
                                    for (el in arr) {
                                        try {
                                            val o = el.jsonObject
                                            val dateStr = o["DISBURSEMENTDATE"]?.jsonPrimitive?.contentOrNull
                                                ?: o["disbursement_date"]?.jsonPrimitive?.contentOrNull
                                                ?: o["DISBURSED_DATE"]?.jsonPrimitive?.contentOrNull
                                                ?: ""
                                            if (dateStr.isNotEmpty()) {
                                                val parts = dateStr.split("-", "/", " ").filter { it.isNotEmpty() }
                                                if (parts.size >= 2) {
                                                    val m = parts[1].toIntOrNull()
                                                    val y = if (parts[0].length == 4) parts[0].toIntOrNull() else null
                                                    if (m == prevMonth && (y == null || y == prevYear)) {
                                                        newCount++
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }

                            val score = (newCount * 2) + memberCount
                            CenterData(
                                center = center,
                                memberCount = memberCount,
                                newMemberCount = newCount,
                                score = score,
                                meetingDay = center.meetingDay
                            )
                        }
                    }
                    // Is batch ka wait karo, phir agla batch
                    val batchResults = deferredList.awaitAll()
                    centerDataList.addAll(batchResults)
                    completedCount += batchResults.size
                    loadingMsg = "$completedCount/${centers.size} centers ka data mil gaya..."
                }

                // 3. Plan generate karo
                loadingMsg = "Plan ban raha hai..."
                val config = PlanGenerator.PlanConfig(
                    auditId = audit.id,
                    auditName = audit.name,
                    branchName = audit.branchName,
                    startDate = audit.startDate,
                    endDate = audit.endDate,
                    centers = centerDataList
                )
                val generated = PlanGenerator.generate(config)
                plan = generated
                screen = "plan"

                // 4. Gemini se optimize/review karwao
                isOptimizing = true
                val workingDays = PlanGenerator.getWorkingDays(audit.startDate, audit.endDate)
                val minTarget = workingDays.size * 4
                val opt = geminiApi.optimizePlan(generated.days, centerDataList, audit.name, minTarget)
                isOptimizing = false
                if (opt.isSuccess) {
                    val o = opt.getOrNull()
                    geminiNotes = o?.notes ?: ""
                    geminiSuggestions = o?.suggestions ?: emptyList()
                    if (o != null && o.notes.isNotEmpty()) {
                        plan = generated.copy(geminiNotes = o.notes)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Plan banane me error"
                screen = "audits"
            }
        }
    }

    fun sharePlan() {
        val p = plan ?: return
        val sb = StringBuilder()
        sb.append("📋 ${p.auditName} - Visit Plan\n")
        sb.append("Branch: ${p.branchName} | ${p.startDate} se ${p.endDate}\n")
        sb.append("Total: ${p.totalCenters} centers | ${p.totalMembers} members | ${p.totalNewMembers} new | Score: ${p.totalScore}\n\n")
        for (day in p.days) {
            sb.append("${day.dayName} (${day.date}) - ${day.staffName}\n")
            for (c in day.centers) {
                sb.append("  • ${c.center.centerName} (${c.center.centerCode}) - ${c.memberCount} members, ${c.newMemberCount} new\n")
            }
            sb.append("\n")
        }
        if (geminiNotes.isNotEmpty()) {
            sb.append("🤖 Gemini: $geminiNotes\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        // Context chahiye — activity se call hoga, yahan simple intent nahi chalega
        // Isliye: plan text ko clipboard ya share sheet ke liye alag se handle karo
    }

    when (screen) {
        "login" -> LoginScreen(
            onLogin = { u, p, g -> doLogin(u, p, g) },
            isLoading = isLoading,
            error = error
        )
        "audits" -> AuditSelectScreen(
            audits = audits,
            isLoading = isLoading,
            error = error,
            onSelect = { generatePlan(it) }
        )
        "loading" -> {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(loadingMsg)
                if (error != null) {
                    Text("❌ $error", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        "plan" -> {
            plan?.let {
                PlanDisplayScreen(
                    plan = it,
                    geminiNotes = geminiNotes,
                    geminiSuggestions = geminiSuggestions,
                    isOptimizing = isOptimizing,
                    onShare = { sharePlan() }
                )
            }
        }
    }
}
