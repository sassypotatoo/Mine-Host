package com.example.server

import android.os.Build
import java.util.concurrent.TimeUnit

object ProcessCompat {
    fun waitFor(process: Process, timeout: Long, unit: TimeUnit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeout, unit)
        }
        
        val timeoutMillis = unit.toMillis(timeout)
        val startTime = System.currentTimeMillis()
        while (process.isAlive()) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                return false
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    fun destroyForcibly(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }
    
    private fun Process.isAlive(): Boolean {
        return try {
            exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }
}

fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean = ProcessCompat.waitFor(this, timeout, unit)
fun Process.destroyForciblyCompat() = ProcessCompat.destroyForcibly(this)
