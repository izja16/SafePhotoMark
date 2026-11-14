# SwiftDraw（脱敏水印 / Safe Photo Mark 内嵌副本）

本目录是 [SwiftDraw](https://github.com/swhitty/SwiftDraw) 的本地副本，用于在运行时不访问网络的情况下将 SVG 水印按目标像素尺寸光栅化，并保留透明通道。

- 上游提交见 `UPSTREAM_COMMIT.txt`
- 库源码（`DOM/Sources`、`SwiftDraw/Sources`）未作修改
- `Package.swift` 已裁成仅包含库目标，详见该文件顶部说明
- 许可证为 zlib，见 `LICENSE.txt`

脱敏水印应用通过 Xcode 本地 Swift Package 引用此目录，`import SwiftDraw` 后使用 `SVG` 与 `CGContext.draw(_:in:)`。
