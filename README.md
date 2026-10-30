# ScreenSub · 实时屏幕翻译

抓取手机正在播放的音视频音频，离线识别并实时翻译成中文，用悬浮窗字幕盖在播放器上。全程本地运行，不上传音频。

**源语言**：中文 / 英语 / 日语 / 韩语 / 俄语（+ 自动检测）→ **中文**（目标语言可换）
**构建环境**：AGP 9.4.1 · Gradle 9.6.0 · Kotlin 2.2.10（AGP 内置）· JDK 25（Studio 自带 JBR）
**目标设备**：荣耀 Magic（MagicOS 10 / Android 16 / API 36），其余机型通用

---

## 一、功能

- **内录优先**：48kHz 内录 + 3:1 降采样；被禁止的 App（DRM / 部分视频平台）自动降级麦克风外录
- **五语离线识别**：基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)，模型按语言分工（见下）
- **实时翻译**：翻译降级链 + 批量接口，单句 300ms 上下
- **悬浮字幕**：当前句 + 上一句双行，按字数自适应停留时长
- **模型可管理**：多源下载（hf-mirror + ModelScope）、断点续传、删除、语言内多模型切换

---

## 二、模型

| 模型 | 语言 | 体积 | 默认用于 |
|---|---|---|---|
| X-ASR | 中 / 英 | 168 MB | 中文（自带标点 + 数字归一） |
| SenseVoice | 中/英/日/韩/粤 + 自动 | 228 MB | 英 / 日 / 韩 / 自动检测 |
| GigaAM v2 | 俄语 | 226 MB | 俄语（唯一选择） |
| ReazonSpeech | 日语 | 154 MB | 日语兜底 |
| Zipformer | 韩语 | 73 MB | 韩语兜底 |

模型运行时按需下载，不在 APK 里。首次使用：设置 → 识别 → 下载；HuggingFace 下不动就打开「使用国内镜像」。

---

## 三、怎么打开这个工程

1. Android Studio → `File → Open` → 选 `ScreenSub` 目录
2. 等 Gradle Sync（首次下载 Gradle 9.6.0 + 依赖，约 5-15 分钟）
3. 连上手机 → ▶️ Run

### 版本为什么是这一套

Android Studio Rabbit 1 自带 JBR 是 **JDK 25**，Java 25 只有 **Gradle 9.1+** 才能运行；AGP 9.4 又要求 Gradle 9.6.0。三者绑死：

```
JDK 25  →  Gradle 9.6.0  →  AGP 9.4.1  →  Kotlin 2.2.10（AGP 内置）
```

**不要单独降级其中任何一个。**

### Gradle Sync 报错对照

| 报错 | 处理 |
|---|---|
| `Unsupported class file major version 69` / `BUG! ... 25.0.x` | Gradle 太老跑不动 JDK 25，确认 wrapper 是 `gradle-9.6.0-bin.zip` |
| `Cannot add extension with name 'kotlin'` | 有模块残留 `org.jetbrains.kotlin.android` 插件，AGP 9 内置 Kotlin，须移除 |
| `gradle-wrapper.jar not found` | 用 Studio 自带的 Gradle 兜底，或让它自己下 9.6.0 |
| JitPack 拉不到 `com.github.k2-fsa.sherpa-onnx` | 网络问题，多 Sync 几次或挂代理 |

---

## 四、第一次使用

1. 设置 → 识别 → 下载模型（按语言下载即可，不用的可以不装）
2. 设置 → 翻译 → 配引擎（不配就只用 ML Kit / MyMemory 免费兜底）
3. 关电池优化 + 开自启动（设置页底部），否则息屏后字幕会断
4. 首页 → 选源语言和音频来源 → 开始字幕 → 授权 → 切到视频 App 播放

**内录可用性**：本地播放器（MX Player / VLC）通常可以；Netflix 等 DRM 一定抓不到；B站/腾讯/爱奇艺按机型动态下发，需实测。内录不通自动降级外录（须外放、不戴耳机、环境安静）。

---

## 五、架构

```
采集(48kHz 内录 / MIC 外录)
  → Silero VAD（自管缓冲：静音 450ms 收口 / 4.2s 硬切，切点在能量低谷）
  → sherpa-onnx ASR（整句累积后一次识别）
  → 标点切句（无标点语言按连接词/词数兜底）
  → 批量翻译（降级链：LLM → 火山云 → 阿里云 → Azure → 谷歌 → MyMemory）
  → 双行悬浮字幕
```

### 模块

| 模块 | 职责 |
|---|---|
| `core:common` | Result / 日志 / 调度器 / 时间工具 |
| `core:audio` | 内录 + 外录、48k→16k 重采样、静音探针 |
| `core:asr` | sherpa-onnx 封装、VAD、模型清单 |
| `core:mt` | 翻译降级链、批量翻译、熔断、术语占位符保护 |
| `core:pipeline` | 整句累积、标点切句、字幕流、显示节奏 |
| `core:overlay` | 悬浮字幕窗 |
| `core:data` | DataStore 设置、模型下载、计费对账 |
| `app` | 前台服务、Compose 界面、权限流程 |

---

## 六、关键实现备忘（改代码前先看）

1. **Android 14+ 必须先 `startForeground(mediaProjection)` 再 `getMediaProjection()`**，顺序颠倒直接崩。
2. **投屏授权每次会话都要重新弹**，token 不能复用，服务用 `START_NOT_STICKY`。
3. **内录按 48 kHz 采集再 3:1 降采样**，不要向系统请求 16 kHz。
4. **静音探针判"被禁"三条件同时成立**：媒体在播 + 音量>0 + 连续 3 秒峰值 < -60 dBFS。
5. **悬浮窗背景不透明度 ≤ 0.55**，否则系统拦截穿透触摸。
6. **火山/阿里云的 V4/V1 签名依赖 Content-Type 精确匹配**：请求体必须用 `ByteArray.toRequestBody`，`String.toRequestBody` 会自动补 `; charset=utf-8`，导致签名校验 401。
7. **火山账单客户端锁 HTTP/1.1**：签名里含 `host` 头，HTTP/2 没有 `host`（只有 `:authority`）。
8. **Release 必须保留 `com.k2fsa.sherpa.onnx.**` 的 keep 规则**，否则 JNI 找不到符号。
9. **模型文件改名要连带清理旧文件**，否则旧文件永久残留占空间（下载器已有 `pruneStaleFiles`）。

---

## 七、已知限制

- DRM 内容（Netflix / Disney+）永远抓不到音频，系统级限制
- 俄语 GigaAM 是字符级模型，**无标点、全小写**，译文偶尔会糊句（已加无标点兜底切分）
- 韩语 SenseVoice 偶有助词连写（`번역을을`），个别句译文偏差
- 内录被禁自动降级有 8-10 秒延迟
- 16 KB page size 需 sherpa-onnx ≥ 1.10.10，本工程用 1.13.5

## License

本项目代码采用 [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0)（见 `LICENSE`）。

- 个人学习、研究、测试、业余项目等**非商业用途**：可自由使用、修改、分发。
- **禁止任何商业用途**（出售、付费服务、广告变现、公司内部业务使用等）。
- 作者保留全部权利（含商用）。如需商用，请单独联系作者授权。

版权 © 2026 January。所依赖的语音模型版权归各自上游作者，请以对应 HuggingFace 仓库许可为准。
