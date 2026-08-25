package com.example.server.engine

import android.content.Context
import java.io.File

object NukkitMOTLog4jManager {
    fun ensureConfig(context: Context, serverDir: File): File {
        val file = File(serverDir, "log4j2.xml")
        if (!file.exists() || file.length() == 0L) {
            val content = """<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss} [%thread/%level]: %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
""".trimIndent()
            file.writeText(content, Charsets.UTF_8)
        }
        return file
    }
}
