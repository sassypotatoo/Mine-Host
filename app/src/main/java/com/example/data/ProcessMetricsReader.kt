package com.example.data

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlin.math.max

class ProcessMetricsReader(private val context: Context) {
    private var lastCpuJiffies: Long? = null
    private var lastWallMillis: Long? = null
    private var currentPid: Long? = null
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun read(pid: Long?): Pair<Float?, Long?> {
        if (pid == null || pid <= 0) {
            reset()
            return null to null
        }

        if (currentPid != pid) {
            reset()
            currentPid = pid
        }

        val procDir = File("/proc/$pid")
        
        // 1. Memory Calculation (Try /proc first, then ActivityManager)
        var ram: Long? = null
        val statusFile = File(procDir, "status")
        if (statusFile.exists()) {
            val statusLines = runCatching { statusFile.readLines() }.getOrNull()
            ram = statusLines?.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
        }
        
        if (ram == null) {
            // Fallback to ActivityManager for memory
            try {
                val memInfo = activityManager.getProcessMemoryInfo(intArrayOf(pid.toInt()))
                if (memInfo.isNotEmpty()) {
                    // totalPss is generally available and more reliable than /proc on Android 11+
                    ram = memInfo[0].totalPss * 1024L 
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 2. CPU Calculation
        val statFile = File(procDir, "stat")
        val statText = if (statFile.exists()) runCatching { statFile.readText() }.getOrNull() else null
        val cpu = statText?.let {
            // stat format: pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt utime stime ...
            // We split after the last ') ' because (comm) can contain spaces/parentheses.
            val parts = it.substringAfterLast(") ").trim().split(' ')
            if (parts.size >= 13) {
                // utime is 14th field, so index 11 after ") "
                // stime is 15th field, so index 12 after ") "
                val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0L
                val total = utime + stime
                val now = System.currentTimeMillis()
                
                val previousCpu = lastCpuJiffies
                val previousWall = lastWallMillis
                
                lastCpuJiffies = total
                lastWallMillis = now
                
                if (previousCpu != null && previousWall != null && now > previousWall) {
                    val cpuTicks = (total - previousCpu).coerceAtLeast(0)
                    val wallSeconds = (now - previousWall) / 1000f
                    // Typical USER_HZ is 100 on Android/Linux. 
                    // This is an estimation. Some high-end devices might use 1000 or 100.
                    val cpuSeconds = cpuTicks / 100f
                    (cpuSeconds / max(wallSeconds, 0.001f) * 100f).coerceIn(0f, 100f)
                } else null
            } else null
        }
        
        return cpu to ram
    }

    private fun reset() {
        lastCpuJiffies = null
        lastWallMillis = null
        currentPid = null
    }
}
