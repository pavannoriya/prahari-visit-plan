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
import kotlinx.coroutines.delay
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

                val asOnDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
                val todayCal = Calendar.getInstance()

                // Audit month se previous month nikalo (new member ke liye)
                val auditCal = Calendar.getInstance()
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    auditCal.time = sdf.parse(audit.startDate) ?: Calendar.getInstance().time
                } catch (_: Exception) {}
                val auditMonth = auditCal.get(Calendar.MONTH) + 1
                val auditYear = auditCal.get(Calendar.YEAR)
                val (prevMonth, prevYear) = PlanGenerator.getPreviousMonth(auditMonth, auditYear)

                // 2. DIN-WISE FETCH: Monday -> Tuesday -> Wednesday -> Thursday -> Friday
                // Har din ke centers alag se fetch karo, phir us din ka staff decide karo
                // Isse Monday ka staff decide hote tak Tuesday ka data aa jayega
                val dayOrder = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
                val centersByDay = mutableMapOf<String, List<CenterData>>()

                // Centers ko meeting day ke hisaab se group karo
                val groupedByDay = centers.groupBy { center ->
                    // Meeting day normalize karo: "Monday", "monday", "Mon" sab "Monday"
                    val md = center.meetingDay.trim()
                    when {
                        md.equals("Monday", ignoreCase = true) || md.equals("Mon", ignoreCase = true) -> "Monday"
                        md.equals("Tuesday", ignoreCase = true) || md.equals("Tue", ignoreCase = true) -> "Tuesday"
                        md.equals("Wednesday", ignoreCase = true) || md.equals("Wed", ignoreCase = true) -> "Wednesday"
                        md.equals("Thursday", ignoreCase = true) || md.equals("Thu", ignoreCase = true) -> "Thursday"
                        md.equals("Friday", ignoreCase = true) || md.equals("Fri", ignoreCase = true) -> "Friday"
                        else -> md // Saturday ya unknown: skip ho jayega (Saturday ko center nahi)
                    }
                }

                for (dayName in dayOrder) {
                    val dayCenters = groupedByDay[dayName] ?: emptyList()
                    if (dayCenters.isEmpty()) continue

                    loadingMsg = "$dayName ke ${dayCenters.size} centers ka data fetch ho raha hai..."
                    val dayDataList = mutableListOf<CenterData>()

                    // Is din ke centers: SAFE batch (5 ek saath + 1 sec gap, API block na ho)
                    val batchSize = 5
                    for ((batchIdx, batch) in dayCenters.chunked(batchSize).withIndex()) {
                        if (batchIdx > 0) {
                            kotlinx.coroutines.delay(1000)
                        }
                        val deferredList = batch.map { center ->
                            async {
                                val membersDeferred = async { prahariApi.getMembers(center.centerId, asOnDate).getOrNull() }
                                val disbDeferred = async { prahariApi.getDisbursements(center.centerId, asOnDate).getOrNull() }
                                val members = membersDeferred.await()
                                val disb = disbDeferred.await()

                                // MEMBER COUNT: OD members hatao!
                                // OD = last collection 3 mahine (90 din) se zyada pehle
                                var validMemberCount = 0
                                if (members != null) {
                                    for (el in members) {
                                        try {
                                            val o = el.jsonObject
                                            val lastColl = o["LASTCOLLECTIONDATE"]?.jsonPrimitive?.contentOrNull
                                                ?: o["last_collection_date"]?.jsonPrimitive?.contentOrNull
                                                ?: o["LASTCOLLECTEDDATE"]?.jsonPrimitive?.contentOrNull
                                                ?: ""
                                            // OD check: last collection 90+ din pehle?
                                            val isOD = if (lastColl.isNotEmpty()) {
                                                PlanGenerator.isODMember(lastColl, todayCal)
                                            } else {
                                                false // Date nahi hai to OD mat mano
                                            }
                                            if (!isOD) validMemberCount++
                                        } catch (_: Exception) {
                                            validMemberCount++ // Parse fail to count karo
                                        }
                                    }
                                }

                                // NEW MEMBERS: disbursement previous month me hua
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

                                // Center score (display ke liye): (New x 5) + 6 (center ka apna)
                                val centerScore = (newCount * 5) + 6
                                CenterData(
                                    center = center,
                                    memberCount = validMemberCount, // OD hatake!
                                    newMemberCount = newCount,
                                    score = centerScore,
                                    meetingDay = dayName
                                )
                            }
                        }
                        val batchResults = deferredList.awaitAll()
                        dayDataList.addAll(batchResults)
                    }

                    centersByDay[dayName] = dayDataList
                    loadingMsg = "$dayName done: ${dayDataList.size} centers, ${dayDataList.sumOf { it.memberCount }} members (OD hatake), ${dayDataList.sumOf { it.newMemberCount }} new"
                }

                // 3. Plan generate karo (din-wise + staff score + diversity)
                loadingMsg = "Plan ban raha hai (din-wise, staff score se)..."
                val config = PlanGenerator.PlanConfig(
                    auditId = audit.id,
                    auditName = audit.name,
                    branchName = audit.branchName,
                    startDate = audit.startDate,
                    endDate = audit.endDate,
                    centersByDay = centersByDay
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
            sb.append("${day.dayName} (${day.date}) - Staff: ${day.staffName} (ID: ${day.staffId})\n")
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
