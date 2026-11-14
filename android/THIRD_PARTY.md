# Third-party licenses

Safe Photo Mark itself is MIT-licensed. The Android app redistributes the
following open-source libraries. Their license texts apply to those
components only.

## AndroidSVG — Apache License 2.0

- Project: https://github.com/BigBadaboom/androidsvg
- Artifact: `com.caverock:androidsvg:1.4`
- Used to rasterize SVG watermarks at the target pixel size.
- AndroidSVG covers common SVG 1.1 features; it is not a complete SVG 2
  implementation.

## AndroidX / Jetpack — Apache License 2.0

Used at runtime, including:

- Jetpack Compose and Material 3
- Activity, Core, Lifecycle, DataStore
- ExifInterface
- DocumentFile
- HeifWriter (`androidx.heifwriter:heifwriter`), for HEIF encoding on
  devices that support it (typically API 28+)

Project pages: https://developer.android.com/jetpack

## Kotlin / kotlinx.coroutines — Apache License 2.0

https://github.com/JetBrains/kotlin
https://github.com/Kotlin/kotlinx.coroutines

## Test-only dependencies

These are not packaged in the APK:

- JUnit 4 (Eclipse Public License 1.0)
- Robolectric (Apache 2.0)
- metadata-extractor by Drew Noakes (Apache 2.0)

The Apache License 2.0 is compatible with redistributing this project
under the MIT License. Full Apache 2.0 text:
https://www.apache.org/licenses/LICENSE-2.0
