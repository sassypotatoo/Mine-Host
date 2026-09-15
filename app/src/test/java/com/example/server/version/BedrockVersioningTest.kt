package com.example.server.version

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.server.template.TemplateRegistry
import com.example.ui.servercreation.WizardStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for Phase C: First-class Bedrock server versioning.
 *
 * Validates:
 * - Wizard step ordering (VERSION before ENGINE)
 * - Catalog contract (Cloudburst range fields)
 * - Engine-version compatibility logic
 * - Version aggregation across engines
 */
@RunWith(RobolectricTestRunner::class)
class BedrockVersioningTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private fun loadCatalogVersions(): List<JSONObject> {
        val root = context.resources
            .openRawResource(R.raw.engine_versions)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val versions = root.getJSONArray("versions")
        return (0 until versions.length()).map { versions.getJSONObject(it) }
    }

    @Test
    fun versionBeforeEngineInWizardFlow() {
        val steps = WizardStep.entries
        val versionIndex = steps.indexOf(WizardStep.VERSION)
        val engineIndex = steps.indexOf(WizardStep.ENGINE)
        assertTrue(
            "VERSION (ordinal=$versionIndex) must come before ENGINE (ordinal=$engineIndex)",
            versionIndex < engineIndex
        )
    }

    @Test
    fun wizardStepCountUnchanged() {
        assertEquals("Wizard must have exactly 8 steps", 8, WizardStep.entries.size)
    }

    @Test
    fun cloudburstCatalogEntryHasRangeFields() {
        val versions = loadCatalogVersions()
        val cloudburst = versions.find { it.getString("id") == "cloudburst:1241" }
        assertNotNull("Cloudburst entry must exist in catalog", cloudburst)
        assertTrue(
            "Cloudburst must have minimumSupportedBedrockVersion",
            cloudburst!!.has("minimumSupportedBedrockVersion")
        )
        assertTrue(
            "Cloudburst must have maximumSupportedBedrockVersion",
            cloudburst.has("maximumSupportedBedrockVersion")
        )
        assertEquals("1.20.70", cloudburst.getString("minimumSupportedBedrockVersion"))
        assertEquals("1.26.30", cloudburst.getString("maximumSupportedBedrockVersion"))
        assertEquals("MULTI_VERSION", cloudburst.getString("compatibilityMode"))
    }

    @Test
    fun multiVersionEnginesHaveRangeOrSupportedList() {
        val versions = loadCatalogVersions()
        val multiVersionEngines = versions.filter {
            it.optString("compatibilityMode") == "MULTI_VERSION" &&
                it.optBoolean("available", true) &&
                !it.optBoolean("historical", false) &&
                !it.getString("id").startsWith("java_")
        }
        assertTrue("Must have at least one MULTI_VERSION engine", multiVersionEngines.isNotEmpty())

        multiVersionEngines.forEach { entry ->
            val id = entry.getString("id")
            val supportedList = entry.optJSONArray("supportedBedrockVersions")
            val hasList = supportedList != null && supportedList.length() > 0
            val hasMin = entry.has("minimumSupportedBedrockVersion")
            val hasMax = entry.has("maximumSupportedBedrockVersion")
            assertTrue(
                "MULTI_VERSION entry $id must have supported list or min+max range",
                hasList || (hasMin && hasMax)
            )
        }
    }

    @Test
    fun singleVersion126_30ShowsCompatibleEngines() {
        val versions = loadCatalogVersions()
        val targetVersion = "1.26.30"

        val compatibleEngines = versions
            .filter { it.optBoolean("available", true) && !it.optBoolean("historical", false) }
            .filter { entry ->
                when (entry.optString("compatibilityMode")) {
                    "SINGLE_VERSION" -> {
                        val supported = entry.optJSONArray("supportedBedrockVersions")
                        val hasVersion = (0 until (supported?.length() ?: 0)).any {
                            supported!!.getString(it) == targetVersion
                        }
                        hasVersion || entry.optString("recommendedBedrockVersion") == targetVersion
                    }
                    "MULTI_VERSION" -> {
                        val min = entry.optString("minimumSupportedBedrockVersion", "")
                        val max = entry.optString("maximumSupportedBedrockVersion", "")
                        min.isNotEmpty() && max.isNotEmpty() && min <= targetVersion && targetVersion <= max
                    }
                    else -> false
                }
            }
            .map { it.getString("engineId") }
            .distinct()

        assertTrue("1.26.30 should be supported by PowerNukkitX", compatibleEngines.contains("bedrock_power_nukkit_x"))
        assertTrue("1.26.30 should be supported by PM1E", compatibleEngines.contains("bedrock_nukkit"))
        assertTrue("1.26.30 should be supported by Nukkit-MOT", compatibleEngines.contains("nukkit-mot"))
        assertTrue("1.26.30 should be supported by Cloudburst (MULTI_VERSION range)", compatibleEngines.contains("bedrock_cloudburst_nukkit"))
    }

    @Test
    fun autoVersionShowsMultiVersionEngines() {
        val versions = loadCatalogVersions()

        val multiVersionEngines = versions
            .filter { it.optBoolean("available", true) && !it.optBoolean("historical", false) }
            .filter { it.optString("compatibilityMode") == "MULTI_VERSION" }
            .map { it.getString("engineId") }
            .distinct()

        assertTrue("AUTO should show Cloudburst", multiVersionEngines.contains("bedrock_cloudburst_nukkit"))
        assertTrue("AUTO should show Nukkit-MOT", multiVersionEngines.contains("nukkit-mot"))
        assertFalse("AUTO should not show PowerNukkitX (SINGLE_VERSION)", multiVersionEngines.contains("bedrock_power_nukkit_x"))
        assertFalse("AUTO should not show PM1E (SINGLE_VERSION)", multiVersionEngines.contains("bedrock_nukkit"))
    }

    @Test
    fun engineInstallabilityAcceptsCloudburstWithRange() {
        val versions = loadCatalogVersions()
        val cloudburst = versions.find { it.getString("id") == "cloudburst:1241" }
        assertNotNull(cloudburst)

        // Verify the entry would pass catalog parsing validation
        val compatibilityMode = cloudburst!!.getString("compatibilityMode")
        assertEquals("MULTI_VERSION", compatibilityMode)

        val supportedList = cloudburst.optJSONArray("supportedBedrockVersions")
        val hasList = supportedList != null && supportedList.length() > 0
        val hasMin = cloudburst.has("minimumSupportedBedrockVersion")
        val hasMax = cloudburst.has("maximumSupportedBedrockVersion")

        // With the fix, Cloudburst has min+max so it passes validation
        assertTrue("Cloudburst must pass MULTI_VERSION validation", hasList || (hasMin && hasMax))
        assertTrue("Cloudburst must have compatibilitySummary", cloudburst.has("compatibilitySummary"))
    }

    @Test
    fun bedrockVersionOptionAggregationIncludesAllSupportedVersions() {
        val versions = loadCatalogVersions()

        // Simulate the aggregation logic from the ViewModel
        data class AggEntry(val version: String, val engines: MutableSet<String>)

        val aggregated = mutableMapOf<String, AggEntry>()

        for (v in versions) {
            val engineId = v.getString("engineId")
            if (TemplateRegistry.isJavaEditionEngine(engineId)) continue
            if (!v.optBoolean("available", true)) continue
            if (v.optBoolean("historical", false)) continue
            if (v.optBoolean("deprecated", false)) continue

            when (v.optString("compatibilityMode")) {
                "SINGLE_VERSION" -> {
                    val supported = v.optJSONArray("supportedBedrockVersions")
                    val bv = v.optString("recommendedBedrockVersion", "").ifEmpty {
                        if (supported != null && supported.length() > 0) supported.getString(0) else ""
                    }
                    if (bv.isNotEmpty()) {
                        aggregated.getOrPut(bv) { AggEntry(bv, mutableSetOf()) }.engines.add(engineId)
                    }
                }
                "MULTI_VERSION" -> {
                    aggregated.getOrPut("AUTO") { AggEntry("AUTO", mutableSetOf()) }.engines.add(engineId)
                }
            }
        }

        assertTrue("Must have 1.26.30 in aggregated versions", aggregated.containsKey("1.26.30"))
        assertTrue("Must have AUTO in aggregated versions", aggregated.containsKey("AUTO"))
        assertTrue("1.26.30 must have at least 2 engines", aggregated["1.26.30"]!!.engines.size >= 2)
        assertTrue("AUTO must have at least 1 engine", aggregated["AUTO"]!!.engines.size >= 1)
    }
}
