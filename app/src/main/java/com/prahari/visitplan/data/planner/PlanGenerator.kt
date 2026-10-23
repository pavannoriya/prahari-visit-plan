package com.prahari.visitplan.data.planner

import com.prahari.visitplan.data.models.CenterData
import com.prahari.visitplan.data.models.DayPlan
import com.prahari.visitplan.data.models.VisitPlan
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Daily Visit Plan Generator
 *
 * Rules (user-finalized 2026-10-23):
 * 1. 1 staff per day only
 * 2. Saturday = working day BUT no center meetings (centers only Mon-Fri)
 * 3. Minimum target = working days x 4 (e.g. Mon-Sat 6 days -> 24 centers min)
 * 4. Daily count FLEXIBLE (some days 4, some 6-7)
 * 5. Staff diversity: in 5 visit days, at least 4 DIFFERENT staff
 * 6. New member = disbursement date in PREVIOUS month (of audit month)
 * 7. Score per center = (New Members x 2) + Members
 */
object PlanGenerator {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayFormat = SimpleDateFormat("EEEE", Locale.US)

    data class PlanConfig(
        val auditId: String,
        val auditName: String,
        val branchName: String,
        val startDate: String, // yyyy-MM-dd
        val endDate: String, // yyyy-MM-dd
        val centers: List<CenterData>
    )

    fun generate(config: PlanConfig): VisitPlan {
        // 1. Working days nikalo (Mon-Sat, Sunday excluded)
        val workingDays = getWorkingDays(config.startDate, config.endDate)

        // 2. Visit days = Mon-Fri only (Saturday ko center nahi)
        val visitDays = workingDays.filter { it.dayOfWeek != Calendar.SATURDAY }

        // 3. Minimum target = working days x 4
        val minTarget = workingDays.size * 4

        // 4. Centers ko score ke hisaab se sort karo (high score pehle)
        val sortedCenters = config.centers.sortedByDescending { it.score }

        // 5. Staff-wise group karo
        val staffGroups = sortedCenters.groupBy { it.center.staffName.ifEmpty { "Unknown" } }

        // 6. Staff ko unke total score ke hisaab se sort karo
        val sortedStaff = staffGroups.entries.sortedByDescending { entry ->
            entry.value.sumOf { it.score }
        }.map { it.key }

        // 7. Din-wise plan banao
        val days = mutableListOf<DayPlan>()
        val usedCenters = mutableSetOf<String>()
        var staffIndex = 0

        // Pehle har din ek alag staff do (diversity ke liye)
        // Phir bache hue centers ko high-score order me baanto
        for ((dayIdx, day) in visitDays.withIndex()) {
            // Staff chunna: pehle 4-5 din me alag-alag staff, phir repeat
            val staffName: String
            if (dayIdx < sortedStaff.size && dayIdx < 5) {
                // Pehle 5 din: har din naya staff (diversity)
                staffName = sortedStaff[dayIdx]
            } else {
                // Baad ke din: round-robin
                staffName = sortedStaff[staffIndex % sortedStaff.size]
            }
            staffIndex++

            // Is staff ke centers (jo abhi use nahi hue)
            val staffCenters = staffGroups[staffName]?.filter {
                !usedCenters.contains(it.center.centerId)
            } ?: emptyList()

            // Aaj kitne centers? Flexible: baaki din aur baaki centers dekh ke
            val remainingDays = visitDays.size - dayIdx
            val remainingCenters = sortedCenters.count { !usedCenters.contains(it.center.centerId) }
            val targetToday = if (remainingDays > 0) {
                // Aaj ka target: baaki centers / baaki din, min 4, max 7
                val avg = remainingCenters / remainingDays
                avg.coerceIn(4, 7)
            } else 4

            // Is staff ke centers me se aaj ke liye chuno
            // Priority: meeting day match kare to pehle, phir score
            val todayCenters = mutableListOf<CenterData>()
            val dayName = day.dayName

            // Pehle meeting-day-match wale
            val matchingDay = staffCenters.filter {
                it.meetingDay.equals(dayName, ignoreCase = true) ||
                it.center.meetingDay.equals(dayName, ignoreCase = true)
            }.sortedByDescending { it.score }

            // Phir baaki (score order)
            val others = staffCenters.filter {
                !matchingDay.contains(it)
            }.sortedByDescending { it.score }

            for (c in matchingDay + others) {
                if (todayCenters.size >= targetToday) break
                todayCenters.add(c)
                usedCenters.add(c.center.centerId)
            }

            // Agar is staff ke centers khatam ho gaye aur aaj ka target poora nahi hua,
            // to dusre staff ke high-score centers le lo (lekin staff rule toot jayega)
            // Isliye: agar staff ke centers kam hain, to aaj utne hi rakho
            // (Better: kam centers, lekin 1-staff rule na toote)

            if (todayCenters.isNotEmpty()) {
                days.add(
                    DayPlan(
                        dayName = day.dayName,
                        date = day.dateStr,
                        staffName = staffName,
                        staffId = todayCenters.firstOrNull()?.center?.staffId ?: "",
                        centers = todayCenters,
                        totalMembers = todayCenters.sumOf { it.memberCount },
                        totalNewMembers = todayCenters.sumOf { it.newMemberCount },
                        totalScore = todayCenters.sumOf { it.score }
                    )
                )
            }
        }

        // 8. Agar min target poora nahi hua aur centers bache hain,
        // to existing dinon me centers add karo (max 7 per day)
        val totalPlanned = days.sumOf { it.centers.size }
        if (totalPlanned < minTarget) {
            val remaining = sortedCenters.filter { !usedCenters.contains(it.center.centerId) }
            var remIdx = 0
            for (day in days) {
                if (remIdx >= remaining.size) break
                val canAdd = 7 - day.centers.size
                if (canAdd > 0) {
                    val toAdd = remaining.subList(remIdx, minOf(remIdx + canAdd, remaining.size))
                    val newCenters = day.centers + toAdd
                    val idx = days.indexOf(day)
                    days[idx] = day.copy(
                        centers = newCenters,
                        totalMembers = newCenters.sumOf { it.memberCount },
                        totalNewMembers = newCenters.sumOf { it.newMemberCount },
                        totalScore = newCenters.sumOf { it.score }
                    )
                    toAdd.forEach { usedCenters.add(it.center.centerId) }
                    remIdx += toAdd.size
                }
            }
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
            totalScore = allPlannedCenters.sumOf { it.score },
            staffCovered = days.map { it.staffName }.distinct()
        )
    }

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
                // Sunday excluded
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

    /**
     * Previous month nikalo (audit month se)
     * e.g. audit Oct 2026 -> previous month = Sep 2026 (month=9, year=2026)
     */
    fun getPreviousMonth(auditMonth: Int, auditYear: Int): Pair<Int, Int> {
        return if (auditMonth == 1) {
            Pair(12, auditYear - 1)
        } else {
            Pair(auditMonth - 1, auditYear)
        }
    }
}
