package com.example.server

import com.example.data.PortTransport
import com.example.data.ServerEdition
import com.example.data.ServerNetworkType
import com.example.data.ServerProfile
import com.example.server.engine.ConfigAdapterFactory
import com.example.server.engine.EngineCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BedrockBaselineIntegrationTest {

    private class FakePortBinder(
        private val availableUdp: Set<Int>,
        private val availableTcp: Set<Int>
    ) : PortBinder {
        override fun canBindUdp(port: Int): Boolean = port in availableUdp
        override fun canBindTcp(port: Int): Boolean = port in availableTcp
    }

    @Test
    fun testDefaultProfileEditionAndNetworkType() {
        val draft = ServerProfile.createDraft(
            name = "Test Server",
            engineId = "bedrock_power_nukkit",
            engineVersionId = "v1",
            bedrockVersion = "1.20.0",
            serverDirectory = "/tmp/test",
            worldSeed = 0L,
            worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
            worldSeedKnown = true
        )
        assertEquals(ServerEdition.BEDROCK, draft.edition)
        assertEquals(ServerNetworkType.BEDROCK_RAKNET_UDP, draft.networkType)
        assertNotNull(draft.id)
        assertEquals("1.20.0", draft.bedrockVersion)
    }

    @Test
    fun testPortAllocatorUdpAndTcpValidation() {
        val originalBinder = PortAllocator.binder
        try {
            PortAllocator.binder = FakePortBinder(
                availableUdp = setOf(19132),
                availableTcp = setOf(25565)
            )

            // Bedrock UDP Port Validation
            val udpResult = PortAllocator.validatePort(19132, PortTransport.UDP)
            assertTrue(udpResult.available)
            assertEquals(19132, udpResult.requestedPort)

            // Java TCP Port Validation Architecture
            val tcpResult = PortAllocator.validatePort(25565, PortTransport.TCP)
            assertTrue(tcpResult.available)
            assertEquals(25565, tcpResult.requestedPort)

            // Unavailable Port
            val unavailableResult = PortAllocator.validatePort(19133, PortTransport.UDP)
            assertFalse(unavailableResult.available)

            // Invalid Port
            val invalidResult = PortAllocator.validatePort(99999, PortTransport.UDP)
            assertFalse(invalidResult.available)
        } finally {
            PortAllocator.binder = originalBinder
        }
    }

    @Test
    fun testConfigAdapterFactoryResolutions() {
        val pnAdapter = ConfigAdapterFactory.getAdapter("PowerNukkit")
        assertNotNull(pnAdapter)

        val pnxAdapter = ConfigAdapterFactory.getAdapter("PowerNukkitX")
        assertNotNull(pnxAdapter)

        val pm1eAdapter = ConfigAdapterFactory.getAdapter("PM1E")
        assertNotNull(pm1eAdapter)

        val nukkitMotAdapter = ConfigAdapterFactory.getAdapter("Nukkit-MOT")
        assertNotNull(nukkitMotAdapter)

        // Unknown adapter must throw IllegalStateException
        try {
            ConfigAdapterFactory.getAdapter("UnknownEngine123")
            fail("Expected IllegalStateException for unknown engine adapter")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No engine configuration adapter registered") == true)
        }
    }

    @Test
    fun testEngineCatalogSpecs() {
        val spec = EngineCatalog.getSpec("bedrock_power_nukkit")
        assertNotNull(spec)
        assertEquals("bedrock_power_nukkit", spec!!.id)
    }

    @Test
    fun testNoCorruptedPrebuiltLauncherInJniLibs() {
        val prebuiltSo = File("src/main/jniLibs/arm64-v8a/libminehost_jvm_launcher.so")
        assertFalse("Corrupted prebuilt launcher must not exist in jniLibs", prebuiltSo.exists())
    }

    @Test
    fun testNativeLauncherCmakeSourceExists() {
        val cmakeLists = File("src/main/cpp/minehost_jvm_launcher/CMakeLists.txt")
        val mainCpp = File("src/main/cpp/minehost_jvm_launcher/main.cpp")
        assertTrue("CMakeLists.txt must exist", cmakeLists.exists())
        assertTrue("main.cpp must exist", mainCpp.exists())
    }
}
