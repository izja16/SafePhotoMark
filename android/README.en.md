# Safe Photo Mark

[简体中文](README.md) · [繁體中文](README.zh-Hant.md) · **English** · [日本語](README.ja.md) · [한국어](README.ko.md)

**Watermark your photos and strip the shooting details that can identify you.**

A **native Android app** for phones and tablets. It batch-processes common formats such as HEIF, JPEG, and PNG on the device. Watermarks can be placed at random while avoiding the center of the frame, and export removes camera settings, capture time, and location by default.

**It does not use the network and never uploads anything.** All work happens on your device: the app has no INTERNET permission, and photos, watermarks, settings, and file names never leave this phone or tablet.

The Chinese name is **脱敏水印**. It requires **Android 8.0 or later**. This is a public beta with many known issues; please wait for the official release. The interface follows the system language and includes Simplified Chinese, Traditional Chinese, English, Japanese, and Korean. There is also a native Apple Silicon Mac app.

## Safety and privacy

- **No network**: the app has no INTERNET permission and does not make network requests.
- **Nothing is uploaded**: photos, watermarks, settings, and file names stay on this device.
- **Local only**: all processing runs on your phone or tablet, not on a server.
- **Sandboxed access**: it only reads and writes the photos, watermark file, and output folder you pick. It does not ask for broad storage or photo-library access.
- **Originals are not overwritten**: results go to a folder you choose, with a `_safephotomark` suffix by default.

## Features

- **Batch watermarking** with a transparent PNG or SVG. Size and opacity are adjustable.
- **Remove metadata (on by default)**. Camera settings, time, and location are cleared. When this is off, shooting details are kept as much as Android allows, but the embedded thumbnail is still dropped and orientation is forced upright.
- **Placement**: switch Manual / Random / Fixed in one row. Manual lets you drag in the preview. Random stays fully inside the canvas and avoids the center 30%. Fixed uses a 9-cell grid.
- **Formats**: JPEG, PNG, HEIF/HEIC, TIFF, GIF, BMP, and WebP the device can decode. Output can be JPEG, PNG, HEIF, TIFF, or same as the source.
- **Random rename**: optional 12-character random file names, so dates are not left in names.
- **Import**: the system photo picker (multiple), or files and folders. Up to 2000 photos per batch.
- **Completion sound**, which can be turned off.
- **Remember settings**, including the last watermark and output folder.
- **Adaptive layout**: preview-first on phones, with Start Processing pinned in a left-aligned bottom bar; a three-pane layout (list | preview | inspector) on tablets and wide screens.

## Install

Version **3.0** is a public beta. It requires **Android 8.0 or later**. There are still many known issues; please wait for the official release.

1. Download `SafePhotoMark-3.0.apk` from [Releases](https://github.com/izja16/SafePhotoMark/releases/latest).
2. Copy the APK to the phone or tablet and open it. If Android blocks “unknown sources,” allow that one installer to install apps. This does not mean the APK is damaged.
3. Open **Safe Photo Mark** from the app list.

The package is not distributed through an app store, so sideloading must be allowed.

## Formats

**Input:** JPEG, PNG, HEIF/HEIC (`.heic`, `.heif`, `.hif`), TIFF, GIF, BMP, and WebP the device can decode. HEIF decode usually needs Android 9 or later.

**Output:** JPEG, PNG, HEIF (`.heic`), TIFF. With “Same as Source,” JPEG / PNG / HEIF / TIFF stay in that format; GIF, BMP, and unknown types export as JPEG; WebP exports as PNG (this app does not write WebP, so alpha can be kept).

GIF and multi-frame HEICS export the first frame only.

Some devices cannot encode HEIF. If you chose HEIF — or Same as Source for a HEIF photo — and the device cannot encode it, that file fails with a clear error. It is never silently rewritten as JPEG.

## What “Remove metadata” strips

When the switch is on, the output is a freshly written file and no source metadata is copied, including EXIF camera tags, capture time, GPS, IPTC/XMP, MakerNote, embedded thumbnails, and camera ICC profiles (output is sRGB).

When it is off, those details are preserved as far as Android allows (JPEG and some HEIF via ExifInterface; PNG and TIFF preservation is limited). Orientation is still forced to “up,” and the original thumbnail is still omitted.

16-bit and wide-gamut sources such as Display P3 are converted to 8-bit sRGB. There is no keep-source-color-space option. A batch can hold at most 2000 photos. Cancel stops unfinished work; files already written are not rolled back.

## Random placement

The watermark stays fully inside the canvas. In Random mode, its center avoids the middle 30% × 30%. If the mark is too large to avoid the center, any in-canvas position is used. Each photo’s placement is remembered and reused on export; you can drag it later. Fixed placement uses a 9-cell grid with about a 4% short-side margin.

## Permissions

The app does not request network access, and it does not request blanket media or storage permissions. Photos come from the system photo picker or the document picker. The watermark file and output folder are granted through the Storage Access Framework and can be remembered.

## Build from source

You need **Android Studio** (Android Gradle Plugin 8.7, JDK 17), Android SDK Platform 35, and Build-Tools 35. Open this directory (it is `android/` in the GitHub repository), wait for Gradle sync, and run the `app` module.

```
./gradlew :app:assembleRelease
./gradlew test
```

Set `sdk.dir` in `local.properties`. The release APK is written under `app/build/outputs/apk/release/`. A local throwaway upload key may be used so the APK can be installed; that key is not committed.

SVG watermarks are rasterized by [AndroidSVG](https://bigbadaboom.github.io/androidsvg/) (Apache 2.0). It covers common SVG, not the full SVG 2 spec. See `THIRD_PARTY.md`.

## License

[MIT License](LICENSE). Copyright (c) 2026 izja16.

## Contact

Questions or suggestions: [izja16xyz@gmail.com](mailto:izja16xyz@gmail.com).
