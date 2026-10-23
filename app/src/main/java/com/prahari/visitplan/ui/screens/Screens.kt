package com.prahari.visitplan.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prahari.visitplan.data.models.Audit
import com.prahari.visitplan.data.models.VisitPlan

// ---------- Login Screen ----------
@Composable
fun LoginScreen(
    onLogin: (String, String, String) -> Unit,
    isLoading: Boolean,
    error: String?
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("📋 Prahari Visit Plan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Daily center visit plan banao — Prahari se data + Gemini se optimization", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Prahari Username (e.g. AUDIT_37204)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Prahari Password") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = geminiKey, onValueChange = { geminiKey = it },
            label = { Text("Gemini API Key (plan optimization ke liye)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Text("⚠️ VPN (InstaSafe) ON rakho. Password/Gemini key sirf tumhare phone me save hogi.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onLogin(username, password, geminiKey) },
            enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Login ho raha hai..." else "Login karo")
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text("❌ $error", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---------- Audit Select Screen ----------
@Composable
fun AuditSelectScreen(
    audits: List<Audit>,
    isLoading: Boolean,
    error: String?,
    onSelect: (Audit) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Audit chuno", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Text("Audits load ho rahe hain...")
        } else if (error != null) {
            Text("❌ $error", color = MaterialTheme.colorScheme.error)
        } else {
            LazyColumn {
                items(audits) { audit ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        onClick = { onSelect(audit) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(audit.name, fontWeight = FontWeight.Bold)
                            Text("Branch: ${audit.branchName} (${audit.branchId})")
                            Text("${audit.startDate} se ${audit.endDate}")
                        }
                    }
                }
            }
        }
    }
}

// ---------- Plan Display Screen ----------
@Composable
fun PlanDisplayScreen(
    plan: VisitPlan,
    geminiNotes: String,
    geminiSuggestions: List<String>,
    isOptimizing: Boolean,
    onShare: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("📋 ${plan.auditName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Branch: ${plan.branchName} | ${plan.startDate} se ${plan.endDate}")
            Spacer(Modifier.height(8.dp))
            Text("Total: ${plan.totalCenters} centers | ${plan.totalMembers} members | ${plan.totalNewMembers} new members | Score: ${plan.totalScore}")
            Text("Staff covered: ${plan.staffCovered.size} (${plan.staffCovered.joinToString(", ")})")
            Spacer(Modifier.height(8.dp))

            if (isOptimizing) {
                Text("🤖 Gemini plan ko review kar raha hai...")
            } else if (geminiNotes.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("🤖 Gemini Review:", fontWeight = FontWeight.Bold)
                        Text(geminiNotes)
                        if (geminiSuggestions.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Suggestions:", fontWeight = FontWeight.Bold)
                            geminiSuggestions.forEach { Text("• $it") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("📤 Plan Share karo")
            }
            Spacer(Modifier.height(16.dp))
        }

        items(plan.days) { day ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${day.dayName} (${day.date})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("👤 Staff: ${day.staffName} | ${day.centers.size} centers | ${day.totalMembers} members | ${day.totalNewMembers} new | Score: ${day.totalScore}")
                    Spacer(Modifier.height(4.dp))
                    day.centers.forEach { c ->
                        Text("  • ${c.center.centerName} (${c.center.centerCode}) — ${c.memberCount} members, ${c.newMemberCount} new, Score: ${c.score}")
                    }
                }
            }
        }
    }
}
