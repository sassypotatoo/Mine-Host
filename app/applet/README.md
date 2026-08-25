# MineHost

A Bedrock dedicated server launcher for Android.

## Run & Build

**Prerequisites:** Android Studio or Gradle 8.13+.

1. Open the project in Android Studio or build via Gradle.
2. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` if required.
3. The archived debug key was unavailable and removed. Default Android debug signing is used unless an external local key is provided.
4. Note that Android update-in-place requires the exact original private key. Generating a new debug key creates a new signing identity.
5. Production and Play Store keys must be managed separately in setting up publishing pipelines.
