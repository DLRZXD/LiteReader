# 极简阅读 (LiteReader)

一款专注于纯净纸感排版、AI 沉浸式外文译读与 **Z-Library 智能直连搜书** 的 Android 阅读器。

## 📦 安装包下载 (v6.9.0 正式版)

- **直接下载（中文文件名）**：[极简阅读-v6.9.0-release.apk](https://github.com/DLRZXD/LiteReader/raw/main/%E6%9E%81%E7%AE%80%E9%98%85%E8%AF%BB-v6.9.0-release.apk)
- **直接下载（英文文件名）**：[LiteReader-v6.9.0-release.apk](https://github.com/DLRZXD/LiteReader/raw/main/LiteReader-v6.9.0-release.apk)
- **支持系统**：Android 8.0 (API 26) 及以上（已针对 Android 15 / API 35 编译，启用 v1/v2/v3 全签名对齐）

## ✨ 核心特性

1. **0.25 秒双轨极速外文翻译 (`BookTranslationEngine.java`, `ReaderActivity.java`)**：
   - **当前屏幕焦点优先（「看哪页、先译哪页」）**：开启翻译时自动定位当前屏幕所在段落优先翻译，无需从章节开头排队等待。
   - **双轨极速上屏**：开启翻译后 **0.25~0.4 秒** 内先通过高速直连通道渲染中文译文上屏，大模型返回后无缝替换为文学级精修译文。
   - **5 大翻译引擎**：内置 **免配置神经网络翻译**、**DeepSeek**、**Qwen**、**Gemini**（支持自定义反代）、**OpenAI**，并提供 **文学小说、社科/哲学、商业/职场、科普/技术** 4 种专业译文语调。
   - **双语对照与纯中文模式**：支持「原文阅读 / 中英双语对照 / 纯中文译文」一键切换，内置逐段防漏译校验与持久化缓存。

2. **Z-Library 智能直连与加密私密书库 (`ZlibMirrorManager.java`, `ZlibActivity.java`)**：
   - **自动优选可用节点**：启动时自动探测并优选高速直连镜像节点，支持一次登录跨节点免填同步。
   - **顶部中文直达搜索与格式筛选**：支持按书名/作者直接检索及按格式（`EPUB` / `PDF` / `AZW3` / `MOBI` / `TXT` / `FB2`）过滤。
   - **可随时取消的流式下载与 AES-256 加密入库**：支持实时下载进度展示、后台下载与一键取消下载，下载完成后自动使用 AES-256 加密存入本地专属书库。

3. **三区触控与多模式翻页体验 (`PagedReaderView.java`, `ReaderActivity.java`)**：
   - **三区触控**：点击屏幕右侧 1/3 下一页，左侧 1/3 上一页，中间 1/3 唤出或收起控制菜单。
   - **双浏览模式与 5 种翻页动画**：支持「左右翻页」与「上下滚动」切换，提供覆盖翻页、平移滑动、仿真翻书、柔和渐变、无动画 5 种过渡效果。
   - **横竖屏与多尺寸自适应**：自适应手机竖屏、横屏、折叠屏与平板网格布局，旋转屏幕自动保持当前阅读位置。

4. **全格式离线解析引擎 (`BookParserEngine.java`)**：
   - 原生支持 **EPUB**、**PDF**（高清渲染与夜间反色护眼）、**AZW3 & MOBI**、**TXT**（自动识别 UTF-8 / GB18030 / UTF-16 编码并智能分章）以及 **FB2**（`.fb2` / `.fb2.zip` / `.fbz`）。

## 🛠️ 本地构建指南

```bash
git clone https://github.com/DLRZXD/LiteReader.git
cd LiteReader
bash build_apk.sh
```
编译完成后，将在工程根目录生成签名对齐的安装包 `LiteReader-v6.9.0-release.apk`。
