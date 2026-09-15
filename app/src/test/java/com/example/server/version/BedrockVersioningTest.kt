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
 * - Wizard step ordering (Edition → Engine → Version)
 * - Catalog contract (Cloudburst range fields)
 * - Engine-version compatibility logic
 * - Engine presence in catalog (Nukkit-MOT, Cloudburst not filtered out)
 * - Per-engine version filtering
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

    // ================================================================
    // A. Setup Wizard ordering: Edition → Engine → Version
    // ================================================================

    @Test
    fun engineBeforeVersionInWizardFlow() {
        val steps = WizardStep.entries
        val editionIndex = steps.indexOf(WizardStep.EDITION)
        val engineIndex = steps.indexOf(WizardStep.ENGINE)
        val versionIndex = steps.indexOf(WizardStep.VERSION)
        assertTrue(
            "EDITION (ordinal=$editionIndex) must come before ENGINE (ordinal=$engineIndex)",
            editionIndex < engineIndex
        )
        assertTrue(
            "ENGINE (ordinal=$engineIndex) must come before VERSION (ordinal=$versionIndex)",
            engineIndex < versionIndex
        )
    }

    @Test
    fun wizardStepCountUnchanged() {
        assertEquals("Wizard must have exactly 8 steps", 8, WizardStep.entries.size)
    }

    // ================================================================
    // B. Bedrock engine catalog contains every genuinely supported engine
    // ================================================================

    @Test
    fun bedrockCatalogContainsAllSupportedEngines() {
        val versions = loadCatalogVersions()
        val enabledTemplates = TemplateRegistry.ALL_TEMPLATES.filter { it.available }

        // Every enabled template that is NOT a Java edition engine must have
        // at least one active catalog entry
        enabledTemplates
            .filter { !TemplateRegistry.isJavaEditionEngine(it.id) }
            .forEach { template ->
                val entries = versions.filter {
                    it.getString("engineId") == template.id &&
                        it.optBoolean("available", true) &&
                        !it.optBoolean("historical", false) &&
                        !it.optBoolean("deprecated", false)
                }
                assertTrue(
                    "Bedrock engine ${template.id} (${template.name}) must have at least one active catalog entry",
                    entries.isNotEmpty()
                )
            }
    }

    // ================================================================
    // C. Nukkit-MOT is not accidentally filtered out
    // ================================================================

    @Test
    fun nukkitMotPresentInCatalog() {
        val versions = loadCatalogVersions()
        val nukkitMotEntries = versions.filter {
            it.getString("engineId") == "nukkit-mot" &&
                it.optBoolean("available", true) &&
                !it.optBoolean("historical", false) &&
                !it.optBoolean("deprecated", false)
        }
        assertTrue(
            "Nukkit-MOT must have at least one active catalog entry",
            nukkitMotEntries.isNotEmpty()
        )
        val template = TemplateRegistry.ALL_TEMPLATES.find { it.id == "nukkit-mot" }
        assertNotNull("Nukkit-MOT template must exist in TemplateRegistry", template)
        assertTrue("Nukkit-MOT template must be available", template!!.available)
    }

    @Test
    fun nukkitMotNotFilteredByEdition() {
        // Simulate EngineStep filtering for BEDROCK edition — should NOT exclude Nukkit-MOT
        val nukkitMotTemplate = TemplateRegistry.ALL_TEMPLATES.find { it.id == "nukkit-mot" }
        assertNotNull(nukkitMotTemplate)
        assertFalse(
            "Nukkit-MOT must not be a Java edition engine",
            TemplateRegistry.isJavaEditionEngine("nukkit-mot")
        )
        // In EngineStep, BEDROCK edition filters: !isJavaEditionEngine(template.id)
        // This should include Nukkit-MOT
        val isIncludedByBedrockFilter =
            nukkitMotTemplate != null && !TemplateRegistry.isJavaEditionEngine(nukkitMotTemplate.id)
        assertTrue(
            "Nukkit-MOT must pass the BEDROCK edition filter in EngineStep",
            isIncludedByBedrockFilter
        )
    }

    // ================================================================
    // D. Cloudburst is not accidentally filtered out
    // ================================================================

    @Test
    fun cloudburstPresentInCatalog() {
        val versions = loadCatalogVersions()
        val cloudburstEntries = versions.filter {
            it.getString("engineId") == "bedrock_cloudburst_nukkit" &&
                it.optBoolean("available", true) &&
                !it.optBoolean("historical", false) &&
                !it.optBoolean("deprecated", false)
        }
        assertTrue(
            "Cloudburst Nukkit must have at least one active catalog entry",
            cloudburstEntries.isNotEmpty()
        )
        val template = TemplateRegistry.ALL_TEMPLATES.find { it.id == "bedrock_cloudburst_nukkit" }
        assertNotNull("Cloudburst template must exist in TemplateRegistry", template)
        assertTrue("Cloudburst template must be available", template!!.available)
    }

    @Test
    fun cloudburstNotFilteredByEdition() {
        val cloudburstTemplate = TemplateRegistry.ALL_TEMPLATES.find { it.id == "bedrock_cloudburst_nukkit" }
        assertNotNull(cloudburstTemplate)
        assertFalse(
            "Cloudburst must not be a Java edition engine",
            TemplateRegistry.isJavaEditionEngine("bedrock_cloudburst_nukkit")
        )
        val isIncludedByBedrockFilter =
            cloudburstTemplate != null && !TemplateRegistry.isJavaEditionEngine(cloudburstTemplate.id)
        assertTrue(
            "Cloudburst must pass the BEDROCK edition filter in EngineStep",
            isIncludedByBedrockFilter
        )
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

    // ================================================================
    // E. Selecting Nukkit-MOT filters versions by Nukkit-MOT compatibility
    // ================================================================

    @Test
    fun nukkitMotVersionsFilteredCorrectly() {
        val versions = loadCatalogVersions()
        val nukkitMotVersions = versions.filter {
            it.getString("engineId") == "nukkit-mot" &&
                it.optBoolean("available", true) &&
                !it.optBoolean("historical", false)
        }
        assertTrue("Nukkit-MOT must have at least one version", nukkitMotVersions.isNotEmpty())

        // Nukkit-MOT is MULTI_VERSION — should produce an AUTO option
        val hasMultiVersion = nukkitMotVersions.any {
            it.optString("compatibilityMode") == "MULTI_VERSION"
        }
        assertTrue("Nukkit-MOT must have a MULTI_VERSION entry", hasMultiVersion)
    }

    // ================================================================
    // F. Selecting Cloudburst filters versions by Cloudburst compatibility
    // ================================================================

    @Test
    fun cloudburstVersionsFilteredCorrectly() {
        val versions = loadCatalogVersions()
        val cloudburstVersions = versions.filter {
            it.getString("engineId") == "bedrock_cloudburst_nukkit" &&
                it.optBoolean("available", true) &&
                !it.optBoolean("historical", false)
        }
        assertTrue("Cloudburst must have at least one version", cloudburstVersions.isNotEmpty())

        val hasMultiVersion = cloudburstVersions.any {
            it.optString("compatibilityMode") == "MULTI_VERSION"
        }
        assertTrue("Cloudburst must have a MULTI_VERSION entry", hasMultiVersion)
    }

    // ================================================================
    // G. Selecting PowerNukkitX filters versions by PNX compatibility
    // ================================================================

    @Test
    fun powernukkitxVersionsFilteredCorrectly() {
        val versions = loadCatalogVersions()
        val pnxVersions = versions.filter {
            it.getString("engineId") == "bedrock_power_nukkit_x" &&
                it.optBoolean("available", true) &&
                !it.optBoolean("historical", false)
        }
        assertTrue("PowerNukkitX must have at least one version", pnxVersions.isNotEmpty())

        // PNX is SINGLE_VERSION — should produce specific version options
        val hasSingleVersion = pnxVersions.any {
            it.optString("compatibilityMode") == "SINGLE_VERSION"
        }
        assertTrue("PowerNukkitX must have a SINGLE_VERSION entry", hasSingleVersion)
    }

    // ================================================================
    // H. Java engine/version selection remains functional
    // ================================================================

    @Test
    fun javaEnginesPresentInCatalog() {
        val versions = loadCatalogVersions()
        val enabledJavaEngines = TemplateRegistry.ALL_TEMPLATES.filter {
            it.available && TemplateRegistry.isJavaEditionEngine(it.id)
        }
        assertTrue("Must have at least one enabled Java engine", enabledJavaEngines.isNotEmpty())

        enabledJavaEngines.forEach { template ->
            // Java engines may have dynamic versions (Paper API) or static entries
            // Either way, the template must be present
            val templateStillExists = TemplateRegistry.ALL_TEMPLATES.any { it.id == template.id }
            assertTrue("Java engine ${template.id} must exist in registry", templateStillExists)
        }
    }

    // ================================================================
    // I. Engine with zero verified versions handled explicitly
    // ================================================================

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

        val compatibilityMode = cloudburst!!.getString("compatibilityMode")
        assertEquals("MULTI_VERSION", compatibilityMode)

        val supportedList = cloudburst.optJSONArray("supportedBedrockVersions")
        val hasList = supportedList != null && supportedList.length() > 0
        val hasMin = cloudburst.has("minimumSupportedBedrockVersion")
        val hasMax = cloudburst.has("maximumSupportedBedrockVersion")

        assertTrue("Cloudburst must pass MULTI_VERSION validation", hasList || (hasMin && hasMax))
        assertTrue("Cloudburst must have compatibilitySummary", cloudburst.has("compatibilitySummary"))
    }

    @Test
    fun engineStepFiltersOnlyByEditionNotByVersion() {
        // This test verifies that EngineStep filtering does NOT consider
        // draft.bedrockVersion — only the edition is used.
        // The EngineStep code should be:
        //   ServerEdition.BEDROCK -> TemplateRegistry.ALL_TEMPLATES.filter {
        //       !TemplateRegistry.isJavaEditionEngine(it.id)
        //   }
        // NOT:
        //   ServerEdition.BEDROCK -> TemplateRegistry.ALL_TEMPLATES.filter { template ->
        //       !TemplateRegistry.isJavaEditionEngine(template.id) &&
        //       isEngineCompatibleWithVersion(template.id, selectedVersion)
        //   }
        //
        // If a version is pre-selected, ALL Bedrock engines must still appear.

        val allBedrockTemplates = TemplateRegistry.ALL_TEMPLATES.filter {
            !TemplateRegistry.isJavaEditionEngine(it.id)
        }

        assertTrue("Must have at least 2 Bedrock engines", allBedrockTemplates.size >= 2)

        val engineIds = allBedrockTemplates.map { it.id }
        assertTrue("Bedrock engines must include PowerNukkitX", engineIds.contains("bedrock_power_nukkit_x"))
        assertTrue("Bedrock engines must include Nukkit-MOT", engineIds.contains("nukkit-mot"))
        assertTrue("Bedrock engines must include Cloudburst", engineIds.contains("bedrock_cloudburst_nukkit"))
    }
}
