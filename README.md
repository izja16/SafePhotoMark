# 脱敏水印 / Safe Photo Mark

脱敏水印（Safe Photo Mark）是一款运行在 **macOS 14+、Apple Silicon（arm64）** 上的原生应用，用来给照片批量添加水印，并在导出时彻底剔除元数据，降低照片被追溯的风险。全部处理都在本地完成，应用不访问网络。

版本：**3.0**  
显示名称：中文 **脱敏水印**（繁体：脫敏水印），英文 / 日文 / 韩文 **Safe Photo Mark**  
应用产物：`SafePhotoMark.app`  
Bundle ID：`app.safephotomark.SafePhotoMark`  
联系：[izja16xyz@gmail.com](mailto:izja16xyz@gmail.com)

## 如何在 Xcode 中打开并运行

请把整个 `SafePhotoMark` 文件夹拷到 Apple Silicon Mac 上。需要 **Xcode 16 或更新版本**（工程使用 Swift 6，并以内嵌本地 Swift 包引用 SwiftDraw）。然后：

1. 双击打开 `SafePhotoMark.xcodeproj`（或用 Xcode：File → Open）。
2. 工程默认使用 **Sign to Run Locally**（`CODE_SIGN_IDENTITY = "-"`，手动签名，`ENABLE_HARDENED_RUNTIME = NO`），在自己的 Mac 上直接 ⌘R 即可运行，不必先选开发团队。这只适合本机开发与自用。若以后要对外分发或公证，需要到 **Signing & Capabilities** 里改回 Automatic、选择你的 Team，并打开 Hardened Runtime，再按 Developer ID 流程签名与公证。
3. 目标选择 “My Mac”，确保 Scheme 是 **SafePhotoMark**。
4. 按 ⌘R 编译并运行。

系统要求：macOS 14 或更新版本，仅构建 arm64，不包含 x86_64。

首次打开时，Xcode 会解析本地 Swift 包 `Vendor/SwiftDraw`。这是内嵌源码，**不需要联网**。

## 功能一览

1. **自动加水印**：把透明底 PNG 或 SVG 叠到每张照片上。
2. **移除元数据（可关）**：默认开启。开启时输出是全新写入的文件，不从原图拷贝任何元数据（见下文）；关闭后会尽量保留拍摄信息，但仍会去掉原图缩略图，并把方向标签改写为正向。
3. **水印格式**：透明 PNG 与 SVG 均可，透明通道会保留。SVG 会按目标像素尺寸重新光栅化，避免放大发糊。
4. **主流静态图片格式**，尤其是 **HEIF/HEIC**（`.heic` / `.heif` / `.hif`），以及 JPEG、PNG、TIFF、GIF、BMP；若系统 ImageIO 能读，也支持 WebP。
5. **可视化大小滑条**：水印宽度为照片较短边的 5%–80%，默认约 20%，保持宽高比。滑条底部不显示刻度。
6. **位置方式**：一行切换「手动 / 随机 / 固定」。手动时请在预览中拖动水印；随机时每张照片避开画面中心 30% 区域随机放置；固定时展开九宫格「方位」选择。
7. 随机模式下，水印会避开画面中心 30% × 30% 区域随机放置，且整枚水印始终不出画（算法见下文）。每张照片独立随机，也可在预览中拖动调整或换一个位置。
8. **输出格式可选**：JPEG、PNG、HEIF、TIFF，以及「与原图相同」。JPEG / HEIF 有质量滑条（50%–100%，默认 95%）。
9. **导入**：支持拖放（可拖入多张，也可拖入文件夹）以及「选择照片」多选面板。
10. **不覆盖原图**：结果只写到你指定的输出文件夹。建议新建并选择名为「脱敏水印输出」的文件夹。文件名保留原主干，加上 `_safephotomark` 后缀和新扩展名；若重名则自动追加 `-2`、`-3`。
11. **处理完成提示音**：批处理正常结束后播放系统提示音，可在设置中关闭。
12. **界面语言**：简体中文、繁体中文、英语、日语、韩语（跟随系统语言；也可在「系统设置 → 通用 → 语言与地区 → 应用」里单独指定）。

另外提供不透明度滑条（0–100%，默认 100%）、实时预览（可拖动水印调整该张照片的位置）、逐张状态与进度，以及完成后「在 Finder 中显示」。

## 元数据是如何剔除的

解码时使用 ImageIO。预览与缩略图走 `kCGImageSourceCreateThumbnailWithTransform` 路径，按显示尺寸缩小并烘焙 EXIF/TIFF 方向。原尺寸导出改用 `CGImageSourceCreateImageAtIndex` 读取完整帧，再按方向标签把像素转正，避免缩略图 API 在带内嵌预览的 HEIF 上可能放大预览层。预览会把像素绘制到一块全新的 **sRGB** 位图上；导出则按目标格式一次重绘：JPEG 使用不透明白底上下文，PNG / HEIF / TIFF 保留预乘透明通道。这样既丢掉相机/设备特有的 ICC 配置文件，也不会让原始 `CGImage` 上可能附着的元数据跟着走。允许在输出中嵌入标准 sRGB 配置。

当前版本会把 16 位以及 Display P3 等广色域原图转换为 8 位 sRGB 后再处理，没有「保持源色彩空间」的开关。

一次最多导入 2000 张；若发现的数量更多，会提示仅导入了前 2000 张及总共发现的张数。批处理会按本机内存在 2–4 路之间选择并发，约 4800 万像素及以上的照片单独处理，避免内存明显高于 2.0。取消只会停止尚未完成的任务，已经写出的文件不会回滚。同一路径的 SVG 水印被覆盖后重新选择，会按文件修改时间和大小重新加载，而不会沿用旧画面。

「移除元数据」开启时，写出只用 `CGImageDestinationCreateWithURL` + `CGImageDestinationAddImage` 创建全新文件，**绝不**调用 `CGImageDestinationAddImageFromSource`，也**绝不**把原图的属性字典（EXIF、IPTC、XMP、GPS、MakerNote、缩略图、定时元数据等）传给目标。图像级属性只显式写入 `Orientation = 1`（像素已经正立）。相机会自定义的 ICC 配置文件也不会被拷贝，输出统一为 sRGB。

关闭该开关时，会把上述拍摄类元数据尽量写回输出文件，但方向标签仍为正向，嵌入的原图缩略图也不会保留，以免带出未加水印的预览。

## 随机位置如何计算

- 水印必须完整落在画布内。
- 「随机」模式下，水印的**中心点**会在画布内随机，但避开照片正中 30% × 30% 的区域（即水平、垂直均不落在 35%–65% 的交集里）。
- 若水印很大，使得避开中心区域后没有合法位置，则退回为在画布内任意合法位置随机。
- 每张照片的位置会保存下来：预览中拖动水印可手动调整该张照片；导出使用同一位置。也可「换一个位置」或「恢复默认位置」。
- 切换位置方式时，已记录的水印位置会清空，按新方式重新计算。固定模式下使用所选九宫格「方位」。

固定位置使用九宫格（含居中），并留出约 4% 短边的边距。

## 支持的格式

**输入：** JPEG、PNG、HEIF/HEIC（`.heic`、`.heif`、`.hif`）、TIFF、GIF、BMP，以及 macOS 14+ ImageIO 可读取的 WebP。

**输出：** JPEG、PNG、HEIF（`.heic`）、TIFF。选择「与原图相同」时：JPEG / PNG / HEIF / TIFF 保持对应格式；GIF、BMP 及无法识别的格式导出为 JPEG；WebP 导出为 PNG（以保留可能的透明通道，因为本应用不写出 WebP）。

**请注意：GIF 只导出第一帧**，作为静态图处理。多帧 HEICS 同样只取第一帧。

## SVG 水印

SVG 光栅化使用内嵌的 [SwiftDraw](https://github.com/swhitty/SwiftDraw)（zlib 许可），源码位于 `Vendor/SwiftDraw`，由 Xcode 作为**本地 Swift Package** 引用。运行时不访问网络。

上游库源码未改动；`Vendor/SwiftDraw/Package.swift` 仅裁成库目标（去掉测试与命令行工具），说明见该文件头部和 `Vendor/SwiftDraw/README-VENDOR.md`。提交哈希见 `Vendor/SwiftDraw/UPSTREAM_COMMIT.txt`。

限制：SwiftDraw 覆盖常见 SVG（路径、形状、渐变、基本滤镜等），但并非完整 SVG 2 规范。部分滤镜、复杂文字排版或非常罕见的特性可能无法还原。SVG 需要能解析出尺寸（宽高或 viewBox）。内嵌的 WOFF/WOFF2 字体在支持范围内会尝试解码。

PNG 水印按原图像素缩放叠加，并保留透明通道。

## 沙盒与权限

开启 App Sandbox，仅申请 `com.apple.security.files.user-selected.read-write`。可以读写你通过打开/保存面板或拖放选择的文件与文件夹，不会申请主目录或网络权限。上次使用的输出文件夹和水印路径会以安全作用域书签的形式记住，便于下次打开。

## 工程结构

```
SafePhotoMark.xcodeproj/    Xcode 工程（含共享 Scheme）
SafePhotoMark/              应用源码、资源、权限与 Info.plist
  SafePhotoMarkApp.swift    入口、菜单、关于面板与「打开方式」代理
  ContentView.swift         三栏窗口
  AppModel.swift            状态编排（导入、预览、批处理、设置）
  SettingsStore.swift       设置快照与安全作用域书签
  ImportService.swift       后台枚举与缩略图解码
  ExportCoordinator.swift   批量导出调度（按内存 2–4 路，超大图降为 1）
  Models.swift              格式、位置、状态等模型
  Processing/               解码、水印、元数据剔除写出、批处理
  Views/                    列表、预览、设置与拖放区域
  Utilities/                安全作用域访问、预览 LRU 缓存
  Assets.xcassets           图标与强调色
SafePhotoMarkTests/         单元测试（水印几何、输出路径、隐私回归、导出尺寸）
Vendor/SwiftDraw/           内嵌 SVG 库（本地 Swift Package）
docs/                       多语言介绍页（可作 GitHub Pages）
README.md
LICENSE
```

界面提供简体中文、繁体中文、英语、日语和韩语，跟随系统或可在系统设置中单独指定。默认窗口约 960×640，并设有最小尺寸，避免控件被裁切。


## 许可证

本项目采用 [MIT License](LICENSE) 开源。

## 联系

有问题或建议，欢迎写信到 [izja16xyz@gmail.com](mailto:izja16xyz@gmail.com)。
