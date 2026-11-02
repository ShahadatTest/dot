# Contributing to Dot

Thanks for wanting to help. Dot is a small, focused app, so the best contributions keep it fast, offline and Nothing-styled.

## Before you start

- **Bugs:** open an issue with the bug template. Include your phone, Android version, and steps to reproduce.
- **Features:** open a feature request first so we can agree on the idea before you write code.
- **Small fixes** (typos, obvious bugs) can go straight to a pull request.

## Building

You need JDK 17 or newer and the Android SDK (platform 36).

```bash
git clone https://github.com/dhananjay6561/dot.git
cd dot
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew assembleDebug
```

The first build downloads the Nothing Glyph SDK into `app/libs/` from a pinned commit and checks its SHA-256. Install on a connected phone with `./gradlew installDebug`.

### Release builds

Release builds are never signed with the debug key. Without signing details, `./gradlew assembleRelease` produces an unsigned APK. To sign, create a keystore once and keep it (and its passwords) somewhere safe and out of git; every future update must be signed with the same key.

```bash
keytool -genkeypair -v -keystore dot-release.jks -alias dot -keyalg RSA -keysize 4096 -validity 10000
```

Then either add a `keystore.properties` at the project root (git-ignored):

```properties
storeFile=dot-release.jks
storePassword=...
keyAlias=dot
keyPassword=...
```

or set `DOT_KEYSTORE`, `DOT_KEYSTORE_PASSWORD`, `DOT_KEY_ALIAS` and `DOT_KEY_PASSWORD` (this is what CI uses).

The Glyph lights need Nothing's API key in release builds: pass `-PnothingApiKey=...`, add `nothingApiKey=...` to `~/.gradle/gradle.properties`, or set `NOTHING_API_KEY`. Without it the SDK's `test` key is used, which only works with Glyph debug mode turned on.

## Guidelines

- **Keep it offline.** Dot has no internet permission and no analytics. Features that need the network won't be merged.
- **Match the look.** Black background, dot-matrix type (Doto), Space Mono labels, a single red accent. Reuse the components in `ui/Components.kt`.
- **Mind performance.** Anything that runs every frame should read state in the draw phase, not during composition. Don't add libraries for something a few lines of code can do.
- **Write code like the code around it:** same naming, same comment density, Kotlin idioms, no dead code.

## Commits and pull requests

- Small, focused commits with short, lowercase, present-tense messages, for example `add queue reorder by drag`.
- One topic per pull request. Describe what changed and how you tested it on a real phone.
- CI must pass. It builds the app on every pull request.
- By contributing you agree that your contributions are licensed under the Apache License, Version 2.0.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Be kind.
