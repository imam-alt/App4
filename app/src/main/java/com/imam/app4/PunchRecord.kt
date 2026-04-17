package com.imam.app4

data class PunchRecord(
    val timestamp: Long,
    val peakAccel: Double,
    val deltaV: Double,
    val momentum: Double,
    val phoneMassKg: Double,
    val sensorName: String
)
