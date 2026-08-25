import java.net.URI
import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

fun configuredNonBlankValue(
    name: String,
    defaultValue: String = "",
): String {
    val gradleValue = providers.gradleProperty(name).orNull?.trim().orEmpty()
    if (gradleValue.isNotBlank()) {
        return gradleValue
    }
    val envValue = providers.environmentVariable(name).orNull?.trim().orEmpty()
    if (envValue.isNotBlank()) {
        return envValue
    }
    return defaultValue
}

val mineHostAuthRedirectUri = configuredNonBlankValue(
    "MINEHOST_AUTH_REDIRECT_URI",
    "minehost://auth/callback",
)

val paperMcContact = configuredNonBlankValue("PAPERMC_CONTACT")

val parsedAuthRedirectUri = URI(mineHostAuthRedirectUri)
val authRedirectScheme = requireNotNull(
    parsedAuthRedirectUri.scheme?.takeIf(String::isNotBlank)
) {
    "MINEHOST_AUTH_REDIRECT_URI must include a URI scheme."
}
val authRedirectHost = requireNotNull(
    parsedAuthRedirectUri.host?.takeIf(String::isNotBlank)
) {
    "MINEHOST_AUTH_REDIRECT_URI must include a host."
}
val authRedirectPath = requireNotNull(
    parsedAuthRedirectUri.path?.takeIf(String::isNotBlank)
) {
    "MINEHOST_AUTH_REDIRECT_URI must include a callback path."
}

android {
  namespace = "com.example"
  compileSdk = 36
  buildToolsVersion = "36.0.0"
  // ndkVersion = "28.2.13676358"
  sourceSets { getByName("main").assets.srcDir("../engine-metadata") }

  defaultConfig {
        applicationId = "com.aistudio.minehost.qweras"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["authRedirectScheme"] =
            authRedirectScheme
        manifestPlaceholders["authRedirectHost"] =
            authRedirectHost
        manifestPlaceholders["authRedirectPath"] =
            authRedirectPath

        buildConfigField("String", "PAPERMC_CONTACT", "\"$paperMcContact\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

  signingConfigs {
    val debugKeystoreFile = file("${rootDir}/debug.keystore")
    if (debugKeystoreFile.exists()) {
      create("debugConfig") {
        storeFile = debugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      val customDebugConfig = signingConfigs.findByName("debugConfig")
      if (customDebugConfig != null) {
        signingConfig = customDebugConfig
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all { test ->
        test.testLogging {
          events("started", "passed", "skipped", "failed")
          setExceptionFormat("full")
        }
      }
    }
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/minehost_jvm_launcher/CMakeLists.txt")
      version = "3.22.1"
    }
  }
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
tasks.withType<Test>().configureEach {
  // CI runners expose no mid-step logs; a hung test must fail fast with the
  // streaming started-events naming the culprit class instead of blocking ~1h.
  timeout.set(Duration.ofMinutes(35))
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  implementation(libs.snakeyaml)
  implementation(libs.commons.compress)
  implementation(libs.xz)
  implementation(libs.androidx.documentfile)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockk)
  testImplementation(libs.roborazzi)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
