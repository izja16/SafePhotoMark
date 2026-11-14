# Safe Photo Mark

[简体中文](README.md) · [繁體中文](README.zh-Hant.md) · **English** · [日本語](README.ja.md) · [한국어](README.ko.md)

**Watermark your photos and clear capture data that could give you away.**

A native Apple Silicon app that batch-processes common formats such as HEIF, JPEG, and PNG on your Mac. Watermarks can be placed at random, away from the center of the frame. Capture data such as camera settings, time, and location is stripped on export by default.

**It runs offline and uploads nothing.** All processing happens on your computer. The app has no network access, so photos, watermarks, settings, and filenames never leave this Mac.

The Chinese name is **脱敏水印**. It requires **Apple M1 or later**, and macOS 14 or later. The interface is available in Simplified Chinese, Traditional Chinese, English, Japanese, and Korean.

<p align="center">
  <img src="docs/readme/icon.png" width="200" alt="Safe Photo Mark icon">
</p>

## Security and privacy

- **Offline:** The app has no network entitlement and does not make network requests.
- **Nothing is uploaded:** Photos, watermarks, settings, and filenames stay on this computer.
- **Local only:** All processing runs on your Mac. Nothing is sent to a server.
- **Sandbox:** The app can only read and write the files and folders you select. It does not request access to your home directory.
- **Originals stay put:** Results are written to the folder you choose, with a `_safephotomark` suffix by default.

## Features

- **Batch watermarks:** Overlay a transparent PNG or SVG, with size and opacity controls, on many photos at once.
- **Metadata removal (on by default):** Clears camera data, capture time, location, and more. Turn it off to keep as much capture data as possible; the embedded thumbnail is still dropped, and orientation is normalized.
- **Placement:** Switch between Manual, Random, and Fixed in one row. Drag the mark in the preview; Random keeps it out of the center 30% and fully on the canvas; Fixed uses a nine-cell grid.
- **Formats:** Especially useful for HEIF/HEIC, and also supports JPEG, PNG, TIFF, GIF, BMP, and WebP when the system can read it. Export to JPEG, PNG, HEIF, TIFF, or match the original.
- **Random filenames:** Optionally rename exports with a 12-character random stem, so the filename doesn’t reveal the capture date.
- **Drag and drop:** Drop in photos or a folder, or use the file picker. Up to 2,000 at a time.
- **Completion sound:** Optionally play a system sound when a batch finishes.
- **Remembered settings:** The output folder and watermark path are remembered for next time.

## Download and install

Version **3.0** requires **Apple M1 or later**, and macOS 14 or later.

1. Download `SafePhotoMark-3.0.dmg` from [Releases](https://github.com/izja16/SafePhotoMark/releases/latest).
2. Open the disk image and drag “Safe Photo Mark” into the Applications folder.
3. The first time you open it, **right-click (or Control-click) the icon and choose Open**, then confirm. The package is not notarized by Apple, so a regular double-click may be blocked. The app itself is not damaged.

Website: [izja16.github.io/SafePhotoMark](https://izja16.github.io/SafePhotoMark/)

## Supported formats

**Input:** JPEG, PNG, HEIF/HEIC (`.heic`, `.heif`, `.hif`), TIFF, GIF, BMP, and WebP when macOS 14+ can decode it.

**Output:** JPEG, PNG, HEIF (`.heic`), TIFF. With “Same as original,” JPEG / PNG / HEIF / TIFF keep their format; GIF, BMP, and unrecognized formats export as JPEG; WebP exports as PNG (this app does not write WebP, so transparency is preserved).

GIF and multi-frame HEICS export only the first frame.

## What metadata is removed

Strip Metadata is on by default. When it is on, the output is written as a new file and does not copy metadata from the original, including:

- Capture settings (camera, lens, shutter, aperture, ISO, focal length, and other EXIF)
- Capture time
- Location (GPS)
- Captions, keywords, copyright, and other IPTC/XMP
- Maker notes
- Embedded thumbnails and other attachments
- The camera’s ICC profile (output is standardized to sRGB)

Turn it off to keep as much capture data as possible. Orientation is still normalized, and the embedded thumbnail is still removed so an unmarked preview cannot leak out.

16-bit and wide-gamut originals such as Display P3 are converted to 8-bit sRGB before processing. There is currently no “preserve source color space” switch. You can import up to 2,000 photos at a time. Canceling stops work that has not finished; files already written are not rolled back.

## Random placement

- The watermark must sit fully on the canvas.
- In Random mode, the mark’s center avoids the middle 30% × 30% of the photo.
- If the mark is too large for that rule to leave a valid position, a random legal position anywhere on the canvas is used.
- Each photo’s position is remembered. You can drag it in the preview, and export uses the same place.

Fixed placement uses a nine-cell grid (including center), with a margin of about 4% of the shorter side.

## Permissions

The app is sandboxed. It only requests read and write access to the files and folders you select, not to your home directory or the network. The last output folder and watermark path are remembered with security-scoped bookmarks.

## Build from source

You need **Xcode 16 or later** (the project uses Swift 6) and a Mac with **Apple M1 or later**. Copy the project folder to your computer, open `SafePhotoMark.xcodeproj`, choose “My Mac” and the **SafePhotoMark** scheme, then press ⌘R.

The project signs to run locally by default, so you do not need to pick a development team first. This is only suitable for running on your own Mac. The first time you open it, Xcode resolves the vendored local Swift package `Vendor/SwiftDraw` without needing a network connection.

SVG watermarks are rasterized by the bundled [SwiftDraw](https://github.com/swhitty/SwiftDraw) library (zlib license) and redrawn at the target size. It covers common SVG, but it is not a complete SVG 2 implementation.

```
SafePhotoMark.xcodeproj/    Xcode project (with a shared scheme)
SafePhotoMark/              App source, resources, entitlements, and Info.plist
SafePhotoMarkTests/         Unit tests
Vendor/SwiftDraw/           Bundled SVG library (local Swift package)
docs/                       Website and icons
README.md                   Simplified Chinese
README.zh-Hant.md           Traditional Chinese
README.en.md                English
README.ja.md                Japanese
README.ko.md                Korean
LICENSE
```

## License

This project is released under the [MIT License](LICENSE). The bundled SwiftDraw library uses the zlib license; see `Vendor/SwiftDraw/`.

## Contact

Questions or suggestions: [izja16xyz@gmail.com](mailto:izja16xyz@gmail.com).
