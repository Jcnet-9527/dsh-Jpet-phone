# 安卓原生悬浮桌宠（android-app）

不依赖 dsh、不依赖浏览器的**独立 App**：装完就是一个真正常驻在手机上的悬浮窗宠物，
任何界面之上都能看到它，锁屏回来还在，可以拖着走、点它、长按出菜单、和它聊天。

- 包名：`io.github.dshpet.mobile`
- 最低系统：Android 8.0（API 26）
- 权限：`SYSTEM_ALERT_WINDOW`（悬浮窗）+ 前台服务 + 网络（下载动画素材 / AI 对话）

> 只想用插件形态的话，这个目录整个可以忽略，看仓库根目录的 README 就行。

## 和插件版的关系

两者**互不依赖**：插件跑在 dsh 手机版网页里，App 跑在系统悬浮窗上，设置各存各的，
装一个、装两个、只装一个都行，删掉其中一个另一个照常工作。

## 编译

需要 JDK 17 + Android SDK（脚本会自动下载到本地工具目录）：

```powershell
# 第一次：装 JDK 17 + Android SDK（约 1 GB，装在 %LOCALAPPDATA%\dsh-pet-android）
powershell -ExecutionPolicy Bypass -File scripts\setup_android.ps1

# 取回 52 MB 源动画（仓库里只放了清单，没放视频本体）
python scripts\fetch_character_videos.py

# 编译（单测 + lint + APK），产物在 dist-android\
powershell -ExecutionPolicy Bypass -File scripts\build_android.ps1
```

只想出包：

```powershell
cd android
.\gradlew.bat :app:assembleDebug -PpetPython="<你的 python>"
```

`petPython` 需要有 Pillow（`pip install pillow`）和 ffmpeg。

## 素材是怎么来的

1. `assets/characters/shenshen/videos/*.webm` 是手绘透明动画源文件（106 段）。
2. `scripts/export_android_assets.py` 用 ffmpeg 把它们转成逐帧 WebP（有损 Q80），
   并生成 `pet/manifest.json`（裁剪框、帧率、分类名、身体锚点）。
3. `scripts/build_asset_packs.py` 把这一份产物拆成两半：
   - **APK 里的 `generated/petAssetsSlim`**：只保留 7 段核心动画（待机 / 转向 / 移动 /
     点击 ×2 / 拖拽）+ 描述全部 106 段的 manifest，所以安装包只有约 30 MB；
   - **`dist-remote/clips/<id>.zip`**：每一段一个 zip，放到静态托管上按需下载。

4. App 第一次用到某段动画时（随机待机 / 菜单点播），从
   `https://cdn.jsdelivr.net/gh/Jcnet-9527/dsh-Jpet-phone-assets@main/clips/<id>.zip`
   下载、解压到私有目录，之后直接从磁盘解码——**不重新编码，所以下下来的和自带的画质完全一样**。
   设置 → 动画素材 可以一键全下（约 430 MB）、清除、或换成自己的镜像。

上架/自建托管时只要保证这个目录结构：

```
<下载源>/clips/<clip id>.zip      # 每段一个包，里面是 f_0001.webp …
<下载源>/manifest.json            # 可选，和 APK 内的一致
```

## 目录

```
android/                Gradle 工程（Kotlin + Compose 设置页，悬浮窗是原生 View）
assets/                 角色源动画 / 音效
pet/                    导出脚本用到的 Python 模块
scripts/                导出、分包、取源视频、编译、安装脚本
```

## 许可证

- 代码：MIT（`LICENSE-APACHE-2.0.txt` 与 `NOTICE.txt` 是上游 Android 工程自带的文件）
- 音效与第三方素材：见 `THIRD_PARTY_NOTICES.md`
- 角色动画素材为个人非商业用途，仓库只作存档与自用构建，**请勿二次分发素材本体**
