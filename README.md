# BrushLife

Android app that reads the usage data stored on the NFC tag in Philips Sonicare brush heads
(NTAG213) and shows how worn the head is. Read-only: it never writes to the tag.

- Min Android 8.0 (API 26), needs a phone with NFC
- Kotlin + Jetpack Compose
- Download the APK from the [Releases](../../releases) page

## Build

```
echo "sdk.dir=/path/to/android/sdk" > local.properties
gradle assembleDebug   # Gradle 8.11+, JDK 17
```

To sign a release build, create `keystore/keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`). That folder is git-ignored.

`tools/make_brush_art.py` regenerates the vector brush-head artwork (`BrushArt.kt`).

See `THIRD_PARTY_NOTICES.md` for attributions. Not affiliated with Philips.
