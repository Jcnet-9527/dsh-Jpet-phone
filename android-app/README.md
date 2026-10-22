# 安卓原生悬浮桌宠（`android-app/`）

这是**独立 App** 工程：装完 APK，宠物就常驻在手机屏幕上，**不需要 dsh、不需要装插件**。

它与仓库根目录的 dsh 插件是**两套并存的实现**，互不影响：

| 用法 | 怎么做 | 说明 |
|---|---|---|
| 只用插件 | `dsh plugin add github:Jcnet-9527/dsh-Jpet-phone` | 走 dsh 手机版宿主 |
| 只装 App | 到 Releases 下载 APK | 独立运行，自带设置页 |
| 两个都要 | 都装上 | 各存各的设置，删掉一个另一个照常 |

## 目录

```
android/     Gradle 工程（Kotlin + Compose 设置页 + View 悬浮窗）
pet/         素材导出时的中间数据
scripts/     导出/分包脚本（Python）
assets/      角色清单与音效占位
```

## 构建

需要 JDK 17 与 Android SDK（gradle wrapper 会自动对接）：

```bash
./android/gradlew.bat -p android :app:assembleDebug -PpetPython=<带 Pillow 的 Python>
```

构建会先跑 `scripts/export_android_assets.py` 把源动画转成逐帧 webp，再由
`scripts/build_asset_packs.py` 拆成「随包核心 7 段」+「其余按段远程下载」。

源视频（约 52 MB）不在仓库里，用 `scripts/fetch_character_videos.py` 从素材仓库
`Jcnet-9527/dsh-Jpet-phone-assets` 拉取即可。

## 动画远程化

APK 只有约 45 MB，因为里面只放了 7 段核心动画（待机/转向/移动/点击×2/拖拽）。
其余 99 段每段一个 zip 存放在素材仓库，第一次用到时自动下载（设置 → 动画素材
可以一键全下、清除、换自建镜像）。下载内容与随包版本画质完全一致，**没有二次编码**。

## 本版（0.6.1）相对 0.6.0 的改动

- **语音模式语义改对**：`voiceSpeak` 从"读回复"升级为"**读所有气泡**"——服务端
  `showPhrase` 是唯一出口，任何文本进气泡（对话回复、点击闲聊、余额播报）都会朗读，
  与输入方式无关；`ChatModule` 里原来那处直接调 TTS 已删除，避免重复朗读。
  录音中的实时转写与下载提示用 `spoken = false` 标记，不会被念出来。
- **两个开关解耦**：模块门控改为 `chat && (voiceChat || voiceSpeak)`，关掉语音输入
  也能只让宠物朗读；设置页拆成「语音模式（朗读所有气泡）」与「语音输入（常驻麦克风）」。
- **麦克风常驻**：`VoiceButton` 不再是一次性按钮——模块启动/选项变化时自动挂上，
  每帧 `positionVoiceButton` 跟随宠物（位置没变不调 `updateViewLayout`），
  点按钮外收起，长按菜单「语音输入」重新打开。
- **三种音色**：新增 piper VITS int8 音色 `xiaoya`（小雅）与 `chaowen`（超文），
  各约 21 MB，22.05 kHz；`VoiceEngine.speaker(dir, timbre)` 按音色选择 matcha 或 VITS
  配置，设置页可切换与**试听**，切换后自动下载缺失模型（`ensureVoiceModels`）。
  模型分片为 `voice/xiaoya.zip.00N`、`voice/chaowen.zip.00N`。
- **修复特效重叠**：`PetView` 新增 `setContentVisible`（alpha 停画，保留命中），
  fx 分支在特效窗解出首帧后隐藏主窗，并继续 `moveWindow` 让触摸区跟随；
  `detachEffect` 恢复显示。此前主窗调用 `present(null)` 是"未解码"语义，旧帧原地保留，
  导致特效窗与冻结的旧画面同时可见。

## 0.6.0 相对 0.5.1 的改动

新增**语音对话**：按住宠物旁的按钮说话 → 本地流式识别（边说边出字）→ 文字发给
DeepSeek → 回复气泡 + 本地 TTS 朗读。识别与合成全部离线，零额外 API 消耗。

- **引擎**：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）——识别用
  streaming zipformer 中英双语 int8（约 50 MB），朗读用 matcha-icefall-zh-baker 女声 +
  vocos 声码器（约 140 MB，含 jieba 词典与数字/日期/多音字正音 fst）。
- **运行库**：官方预构建 `sherpa-onnx-1.13.8.aar`（static-link onnxruntime 版）直接
  引入 `app/libs/`，abiFilters 限 arm64-v8a/armeabi-v7a，APK 约增 12 MB。
- **模型分发**：与动画分包共用同一组镜像（jsDelivr 优先）；zip 切成 ≤18 MB 分片
  （`voice/asr.zip.00N`、`voice/tts.zip.00N`）绕开 jsDelivr 单文件 20 MB 限制，
  App 端下载后拼接解包，`VoiceModels` 管理就绪态/进度/删除。
- **交互**（0.6.1 起改为常驻，见上）：长按菜单「语音对话」→ 服务在宠物旁挂 `VoiceButton` 悬浮窗（56dp 圆钮）；
  按住开始 `AudioRecord` 16 kHz 采集，松手 `OnlineRecognizer` 出最终文本交 `ChatModule.send`；
  回复经 `VoiceBridge.speak` 进 `SpeechPlayer`（新句子打断旧朗读）。
- **权限**：新增 `RECORD_AUDIO`（运行时申请，悬浮窗内触发时跳设置页代申请）。
- **设置**：「语音对话」区块——开关、朗读开关、语速、模型下载/删除/占用。
- **测试**：`VoiceModelsTest` 用注入 fetcher 的本地文件流覆盖分片拼接、镜像回退、
  损坏包判定、嵌套目录；`PetOptionsTest` 增语音开关门控与语速钳制。
