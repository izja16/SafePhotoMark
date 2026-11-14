# 脱敏水印

**简体中文** · [繁體中文](README.zh-Hant.md) · [English](README.en.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

**给照片加水印，并清掉会暴露你的拍摄信息。**

原生 Android 应用，适配手机和平板。在本地批量处理 HEIF、JPEG、PNG 等常见格式。水印可以随机避开画面中心，导出时默认移除相机参数、拍摄时间和地理位置。

**不联网，不上传任何信息。** 全部处理都在你的设备上完成：应用没有网络权限，照片、水印、设置和文件名都不会离开这台手机或平板。

英文名 **Safe Photo Mark**。系统需求为 **Android 8.0 或更新版本**。界面提供简体中文、繁体中文、英语、日语和韩语，跟随系统语言。当前为公测，已知问题还比较多，正式版敬请期待。另外还有适用于 Apple Silicon 的原生 Mac 应用。

## 安全与隐私

- **不联网**：应用没有网络权限（不会申请 `INTERNET`），也不会发起网络请求。
- **不上传任何信息**：照片、水印、设置和文件名都只留在这台设备上。
- **只在本地运行**：全部处理都在你的手机或平板上完成，不经过任何服务器。
- **沙盒限制**：只读写你选中的照片、水印文件和输出文件夹，不申请整个存储或相册权限。
- **不覆盖原图**：结果写到你指定的文件夹，默认加上 `_safephotomark` 后缀。

## 功能

- **批量加水印**：透明 PNG 或 SVG，可调大小和透明度，一次处理多张照片。
- **移除元数据（默认开启）**：清除拍摄参数、时间、地理位置等；关闭后会尽量保留拍摄信息，但仍会去掉原图缩略图，并把方向改成正向。
- **位置**：一行切换「手动 / 随机 / 固定」。手动可在预览里拖动；随机会避开画面中心 30% 区域，且水印始终不出画；固定使用九宫格方位。
- **格式**：支持 JPEG、PNG、HEIF/HEIC、TIFF、GIF、BMP，以及设备能读取的 WebP。输出可选 JPEG、PNG、HEIF、TIFF，或与原图相同。
- **随机重命名**：可把输出改成 12 位随机文件名，避免文件名里的拍摄日期。
- **导入照片**：可用系统相册选择器多选，也可从文件或文件夹导入。单次最多 2000 张。
- **处理完成提示音**：批处理结束后可播放提示音，能在设置里关闭。
- **记住设置**：输出文件夹和水印路径会记住，下次打开还能用。
- **自适应界面**：手机上预览优先、设置可滚动，开始按钮固定在底部左侧；平板和宽屏使用与桌面版相近的三栏布局（列表 | 预览 | 设置）。

## 下载与安装

当前版本 **3.0**，仅为公测。需要 **Android 8.0 或更新版本**。已知问题还比较多，正式版敬请期待。

1. 从 [Releases](https://github.com/izja16/SafePhotoMark/releases/latest) 下载 `SafePhotoMark-3.0.apk`。
2. 把 APK 拷到手机或平板上并打开。若系统提示「禁止安装未知来源应用」，请按提示允许，这不是应用损坏。
3. 安装完成后，在应用列表里打开「脱敏水印」。

本安装包尚未经过应用商店签名审核，需要你手动允许安装。

## 支持的格式

**输入：** JPEG、PNG、HEIF/HEIC（`.heic`、`.heif`、`.hif`）、TIFF、GIF、BMP，以及设备能解码的 WebP。HEIF 解码通常需要 Android 9 或更新版本。

**输出：** JPEG、PNG、HEIF（`.heic`）、TIFF。选择「与原图相同」时，JPEG / PNG / HEIF / TIFF 保持对应格式；GIF、BMP 及无法识别的格式导出为 JPEG；WebP 导出为 PNG（本应用不写出 WebP，这样可保留透明通道）。

GIF 和多帧 HEICS 都只导出第一帧。

部分设备无法编码 HEIF。若你选择了 HEIF，或「与原图相同」且原图是 HEIF，而设备无法编码，该文件会明确失败，而不会悄悄改存为 JPEG。

## 元数据会移除什么

「移除元数据」默认开启。开启时，输出是全新写入的文件，不会从原图拷贝元数据，包括：

- 拍摄参数（相机、镜头、快门、光圈、ISO、焦距等 EXIF）
- 拍摄时间
- 地理位置（GPS）
- 描述、关键词、版权等 IPTC/XMP
- 厂家私有数据（MakerNote）
- 原图缩略图及其他附加信息
- 相机自带的 ICC 颜色配置文件（输出统一为 sRGB）

关闭后会尽量保留拍摄信息（在 Android 上，JPEG 和部分 HEIF 可通过系统 ExifInterface 写回；PNG 和 TIFF 的保留能力有限），但方向标签仍会改成正向，原图缩略图仍会去掉，以免带出未加水印的预览。

16 位和 Display P3 等广色域原图会转换为 8 位 sRGB 后再处理，目前没有「保持源色彩空间」的开关。一次最多导入 2000 张；取消只会停止尚未完成的任务，已经写出的文件不会回滚。

## 随机位置

- 水印必须完整落在画布内。
- 随机模式下，水印中心会避开照片正中 30% × 30% 的区域。
- 若水印太大、避开中心后没有合法位置，则在画布内任意合法位置随机。
- 每张照片的位置会记住，可在预览里拖动，导出使用同一位置。

固定位置使用九宫格（含居中），并留出约 4% 短边的边距。

## 权限

应用不会申请网络权限，也不会申请整个相册或存储的读取权限。照片通过系统照片选择器或文件选择器导入；水印和输出文件夹通过系统文件选择器授权，并可记住上次选择的位置。

## 从源码编译

需要 **Android Studio Ladybug / Koala 或更新版本**（Android Gradle Plugin 8.7，JDK 17），以及 Android SDK Platform 35 与 Build-Tools 35。

1. 用 Android Studio 打开本目录（GitHub 仓库中为 `android/`）。
2. 等待 Gradle 同步完成。
3. 选择应用模块 `app`，连接设备或启动模拟器，点击 Run。

命令行（需已安装 JDK 17 与 Android SDK，并在 `local.properties` 中设置 `sdk.dir`）：

```
./gradlew :app:assembleRelease
./gradlew test
```

发布版 APK 会出现在 `app/build/outputs/apk/release/`。本地构建可以使用一次性上传密钥签名，便于直接安装；该密钥不会提交到版本库。

SVG 水印由 [AndroidSVG](https://bigbadaboom.github.io/androidsvg/)（Apache 2.0 许可）光栅化，并按目标尺寸重新绘制。它覆盖常见 SVG，但不是完整的 SVG 2。第三方许可说明见 `THIRD_PARTY.md`。

```
app/                        应用源码、资源和单元测试
gradle/                     Gradle Wrapper
README.md                   简体中文
README.zh-Hant.md           繁體中文
README.en.md                English
README.ja.md                日本語
README.ko.md                한국어
LICENSE
THIRD_PARTY.md
```

## 许可证

本项目采用 [MIT License](LICENSE) 开源。Copyright (c) 2026 izja16。依赖库的许可见 `THIRD_PARTY.md`。

## 联系

有问题或建议，欢迎写信到 [izja16xyz@gmail.com](mailto:izja16xyz@gmail.com)。
