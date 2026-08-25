# Nukkit-MOT artifact resolution

MineHost first attempts the exact catalog-pinned artifact and verifies its
publisher SHA-256.

If that exact artifact is unavailable, only the catalog entry for the official
`master` Jenkins job may use runtime resolution. MineHost queries the official
`motci.cn` job, accepts only successful builds whose exact artifact path is
`target/Nukkit-MOT-SNAPSHOT.jar`, validates the JAR, hashes the downloaded
bytes, and commits an app-private resolved-identity sidecar containing the
actual build, URL, size, source revision, timestamp, and SHA-256.

No mutable last-known-good build is packaged in the APK. An app-private
last-known-good entry is written only after the exact downloaded bytes pass all
checks, and it is invalidated if the URL, source identity, size, or SHA changes.
The historical `java25` job is never allowed to fall back to the `master` job.
