package com.prahari.visitplan.data.models

data class Audit(
    val id: String,
    val name: String,
    val branchId: String,
    val branchName: String,
    val startDate: String,
    val endDate: String
)

data class Center(
    val centerId: String,
    val centerCode: String,
    val centerName: String,
    val meetingDay: String,
    val meetingTime: String,
    val staffName: String = "",
    val staffId: String = "",
    val branchName: String = ""
)

data class CenterData(
    val center: Center,
    val memberCount: Int = 0,
    val newMemberCount: Int = 0,
    val score: Int = 0, // (NewMembers x 2) + Members
    val meetingDay: String = ""
) {
    fun calcScore(): Int = (newMemberCount * 2) + memberCount
}

data class DayPlan(
    val dayName: String, // Monday, Tuesday...
    val date: String, // YYYY-MM-DD
    val staffName: String,
    val staffId: String,
    val centers: List<CenterData>,
    val totalMembers: Int = 0,
    val totalNewMembers: Int = 0,
    val totalScore: Int = 0
)

data class VisitPlan(
    val auditId: String,
    val auditName: String,
    val branchName: String,
    val startDate: String,
    val endDate: String,
    val days: List<DayPlan>,
    val totalCenters: Int = 0,
    val totalMembers: Int = 0,
    val totalNewMembers: Int = 0,
    val totalScore: Int = 0,
    val staffCovered: List<String> = emptyList(),
    val geminiNotes: String = ""
)
