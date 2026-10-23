package com.prahari.visitplan.data.planner

import com.prahari.visitplan.data.models.CenterData
import com.prahari.visitplan.data.models.DayPlan
import com.prahari.visitplan.data.models.VisitPlan
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Daily Visit Plan Generator — FINAL PLAN (user-confirmed 2026-10-23)
 *
 * Rules:
 * 1. 1 din = 1 staff STRICT (kabhi 2 staff mix nahi)
 * 2. Centers ONLY Mon-Fri (Saturday ko meeting nahi)
 * 3. Staff Score = (New members x 5) + (Centers x 6)
 * 4. Diversity: 5 din me kam se kam 3 alag staff
 *    - Jab tak 3 alag staff nahi hue: UNUSED staff me se highest score chuno
 *    - 3 staff ho jane ke baad: overall highest score chuno (repeat chalega)
 * 5. OD member = last collection 3 mahine se zyada pehle -> total members me count MAT karo
 * 6. New member = disbursement previous month me hua
 * 7. Meeting day = CenterMeetingDay field se
 * 8. Plan me har din staff ka NAAM + ID saaf dikhega
 * 9. Din-wise: Monday -> Tuesday -> Wednesday -> Thursday -> Friday
 */
object PlanGenerator {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayFormat = SimpleDateFormat("EEEE", Locale.US)

    data class PlanConfig(
        val auditId: String,
        val auditName: String,
        val branchName: String,
        val startDate: String,
        val endDate: String,
        // Day-wise centers: Map<DayName, List<CenterData>>
        // Har din ke centers alag se (meeting day ke hisaab se)
        val centersByDay: Map<String, List<CenterData>>
    )

    /**
     * Staff score nikalo: (New members x 5) + (Centers x 6)
     */
    fun calcStaffScore(centers: List<CenterData>): Int {
        val totalNew = centers.sumOf { it.newMemberCount }
        val totalCenters = centers.size
        return (totalNew * 5) + (totalCenters * 6)
    }

    fun generate(config: PlanConfig): VisitPlan {
        val workingDays = getWorkingDays(config.startDate, config.endDate)
        val visitDays = workingDays.filter { it.dayOfWeek != Calendar.SATURDAY }
        val minTarget = workingDays.size * 4

        val days = mutableListOf<DayPlan>()
        val usedStaff = mutableSetOf<String>() // Staff IDs jo use ho chuke

        for (day in visitDays) {
            val dayName = day.dayName
            // Is din ke centers (meeting day match)
            val dayCenters = config.centersByDay[dayName]
                ?: config.centersByDay[dayName.lowercase()]
                ?: emptyList()

            if (dayCenters.isEmpty()) continue

            // Staff-wise group karo
            val staffGroups = dayCenters.groupBy { it.center.staffId.ifEmpty { it.center.staffName } }

            // Har staff ka score nikalo
            val staffScores = staffGroups.map { (staffKey, centers) ->
                val staffName = centers.firstOrNull()?.center?.staffName ?: ""
                val staffId = centers.firstOrNull()?.center?.staffId ?: staffKey
                StaffScore(staffId, staffName, centers, calcStaffScore(centers))
            }.sortedByDescending { it.score }

            if (staffScores.isEmpty()) continue

            // Staff chuno: Diversity rule
            val selected: StaffScore
            if (usedStaff.size < 3) {
                // Abhi 3 alag staff nahi hue: UNUSED me se highest score chuno
                val unused = staffScores.filter { !usedStaff.contains(it.staffId) }
                selected = if (unused.isNotEmpty()) {
                    unused.first() // Highest score among unused (already sorted)
                } else {
                    staffScores.first() // Sab use ho chuke, to highest score
                }
            } else {
                // 3 staff ho gaye: overall highest score chuno (repeat chalega)
                selected = staffScores.first()
            }

            usedStaff.add(selected.staffId)

            // Is staff ke centers (score order me)
            val selectedCenters = selected.centers.sortedByDescending { it.score }

            days.add(
                DayPlan(
                    dayName = day.dayName,
                    date = day.dateStr,
                    staffName = selected.staffName,
                    staffId = selected.staffId,
                    centers = selectedCenters,
                    totalMembers = selectedCenters.sumOf { it.memberCount },
                    totalNewMembers = selectedCenters.sumOf { it.newMemberCount },
                    totalScore = selected.score
                )
            )
        }

        val allPlannedCenters = days.flatMap { it.centers }
        return VisitPlan(
            auditId = config.auditId,
            auditName = config.auditName,
            branchName = config.branchName,
            startDate = config.startDate,
            endDate = config.endDate,
            days = days,
            totalCenters = allPlannedCenters.size,
            totalMembers = allPlannedCenters.sumOf { it.memberCount },
            totalNewMembers = allPlannedCenters.sumOf { it.newMemberCount },
            totalScore = days.sumOf { it.totalScore },
            staffCovered = days.map { "${it.staffName} (${it.staffId})" }.distinct()
        )
    }

    private data class StaffScore(
        val staffId: String,
        val staffName: String,
        val centers: List<CenterData>,
        val score: Int
    )

    data class WorkingDay(
        val dateStr: String,
        val dayName: String,
        val dayOfWeek: Int
    )

    fun getWorkingDays(startDate: String, endDate: String): List<WorkingDay> {
        val days = mutableListOf<WorkingDay>()
        try {
            val start = dateFormat.parse(startDate) ?: return days
            val end = dateFormat.parse(endDate) ?: return days
            val cal = Calendar.getInstance()
            cal.time = start
            while (!cal.time.after(end)) {
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                if (dow != Calendar.SUNDAY) {
                    days.add(
                        WorkingDay(
                            dateStr = dateFormat.format(cal.time),
                            dayName = dayFormat.format(cal.time),
                            dayOfWeek = dow
                        )
                    )
                }
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        } catch (_: Exception) {
        }
        return days
    }

    fun getPreviousMonth(auditMonth: Int, auditYear: Int): Pair<Int, Int> {
        return if (auditMonth == 1) {
            Pair(12, auditYear - 1)
        } else {
            Pair(auditMonth - 1, auditYear)
        }
    }

    /**
     * Check karo ki member OD hai ya nahi
     * OD = last collection 3 mahine (90 din) se zyada pehle
     */
    fun isODMember(lastCollectionDateStr: String, todayCal: Calendar = Calendar.getInstance()): Boolean {
        if (lastCollectionDateStr.isEmpty()) return false
        try {
            // Date formats: yyyy-MM-dd, dd-MM-yyyy, yyyy-MM-dd HH:mm:ss
            val datePart = lastCollectionDateStr.split(" ")[0]
            val parts = datePart.split("-", "/")
            if (parts.size < 3) return false
            val cal = Calendar.getInstance()
            if (parts[0].length == 4) {
                // yyyy-MM-dd
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            } else {
                // dd-MM-yyyy
                cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
            }
            val diffMs = todayCal.timeInMillis - cal.timeInMillis
            val diffDays = diffMs / (1000 * 60 * 60 * 24)
            return diffDays > 90
        } catch (_: Exception) {
            return false
        }
    }
}
