# Patch Report

- Fixed AGP missing generated native executables by explicitly registering the `libminehost_jvm_launcher.so` via `PackageMineHostLauncherTask` variant API.
- Re-architected JavaRuntimeManager.kt to strictly require the launcher binary in `nativeLibraryDir`, removing the dangerous fallback copy into `filesDir`.
- Fixed the Readiness protocol checking loop. RakNet response tracking (`networkReady`) now accurately requires both a matching advertised port and matching Bedrock protocol logic before signaling ONLINE state.
- Rewrote the testing assertions (`testRakNetFirstThenMarkerOnline` and added missing edge cases) to ensure correct strict network readiness checks.
- Upgraded the metadata generation inside `JvmServerEngineBase.kt` to fully populate `InstalledEngineVersion` during normal installation downloads, persisting proper telemetry and origins via `Downloader.getTrustedChecksumForInstall`.
- Replaced the duplicate script with a canonical `tools/verify_native_launcher_in_apk.sh` to prevent packaging blindspots.

The project Gradle wrappers remain untouched.
