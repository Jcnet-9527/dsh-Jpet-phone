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

## 本版（0.6.0）相对 0.5.1 的改动

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
- **交互**：长按菜单「语音对话」→ 服务在宠物旁挂 `VoiceButton` 悬浮窗（56dp 圆钮）；
  按住开始 `AudioRecord` 16 kHz 采集，松手 `OnlineRecognizer` 出最终文本交 `ChatModule.send`；
  回复经 `VoiceBridge.speak` 进 `SpeechPlayer`（新句子打断旧朗读）。
- **权限**：新增 `RECORD_AUDIO`（运行时申请，悬浮窗内触发时跳设置页代申请）。
- **设置**：「语音对话」区块——开关、朗读开关、语速、模型下载/删除/占用。
- **测试**：`VoiceModelsTest` 用注入 fetcher 的本地文件流覆盖分片拼接、镜像回退、
  损坏包判定、嵌套目录；`PetOptionsTest` 增语音开关门控与语速钳制。
