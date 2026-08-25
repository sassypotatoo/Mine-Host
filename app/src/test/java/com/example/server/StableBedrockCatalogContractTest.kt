package com.example.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.server.engine.ConfigAdapterFactory
import com.example.server.engine.EngineCatalog
import com.example.server.template.TemplateRegistry
import com.example.server.version.JavaRuntimeSelector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StableBedrockCatalogContractTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun everyEnabledTemplateHasOneVerifiableBaseline() {
        val root = context.resources
            .openRawResource(R.raw.engine_versions)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val versions = root.getJSONArray("versions")

        val activeByEngine = buildMap<String, MutableList<JSONObject>> {
            for (index in 0 until versions.length()) {
                val entry = versions.getJSONObject(index)
                val active = entry.optBoolean("available", true) &&
                    !entry.optBoolean("historical", false) &&
                    !entry.optBoolean("deprecated", false)
                if (active) {
                    getOrPut(entry.getString("engineId")) {
                        mutableListOf()
                    }.add(entry)
                }
            }
        }

        val enabledTemplates = TemplateRegistry.ALL_TEMPLATES
            .filter { it.available }

        assertFalse(enabledTemplates.isEmpty())

        enabledTemplates.forEach { template ->
            val entries = activeByEngine[template.id].orEmpty()
            assertFalse(
                "Enabled engine ${template.id} has no active catalog build",
                entries.isEmpty(),
            )

            entries.forEach { entry ->
                val spec = EngineCatalog.getSpec(template.id)
                assertNotNull(
                    "Missing launch spec for ${template.id}",
                    spec,
                )
                assertEquals(
                    entry.getString("jarFileName"),
                    spec?.jarName,
                )

                ConfigAdapterFactory.getAdapter(template.id)

                val requiredJava =
                    entry.optInt("requiredJavaVersion", 21)
                val runtimeJava = entry.optInt(
                    "runtimeJavaVersion",
                    JavaRuntimeSelector.select(requiredJava) ?: -1,
                )
                assertTrue(
                    "Invalid runtime Java $runtimeJava for ${entry.getString("id")}",
                    JavaRuntimeSelector.isValid(
                        requiredJava,
                        runtimeJava,
                    ),
                )

                val downloadUrl = entry.getString("downloadUrl")
                assertTrue(downloadUrl.startsWith("https://"))

                val sha = entry.optString("sha256", "")
                val officialResolvedFallback =
                    (template.id == "nukkit-mot" &&
                        downloadUrl.contains(
                            "/job/Nukkit-MOT/job/master/"
                        )) ||
                        (template.id == "java_paper" &&
                            (entry.optString("sourceType") == "PAPER_API" ||
                                downloadUrl.contains("papermc.io") ||
                                downloadUrl == "PLACEHOLDER"))
                assertTrue(
                    "Active build ${entry.getString("id")} has no trusted artifact policy",
                    sha.matches(Regex("^[0-9a-fA-F]{64}$")) ||
                        officialResolvedFallback,
                )

                val protocols = entry.optJSONArray("protocolVersions")
                val selectedVersion = entry
                    .optString("recommendedBedrockVersion", "")
                assertTrue(
                    "Active build ${entry.getString("id")} has no protocol or version evidence",
                    (protocols != null && protocols.length() > 0) ||
                        (
                            selectedVersion.isNotBlank() &&
                                !selectedVersion.equals(
                                    "AUTO",
                                    ignoreCase = true,
                                )
                        ) ||
                        officialResolvedFallback,
                )
            }
        }
    }
}
