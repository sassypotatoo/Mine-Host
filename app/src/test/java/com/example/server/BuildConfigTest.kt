package com.example.server

import com.example.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuildConfigTest {
    @Test
    fun printBuildConfig() {
        println("DEBUG: PAPERMC_CONTACT='${BuildConfig.PAPERMC_CONTACT}'")
        println("DEBUG: VERSION_NAME='${BuildConfig.VERSION_NAME}'")
    }
}
