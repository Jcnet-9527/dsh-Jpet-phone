# DSH 桌宠 · 安卓悬浮版（定制分支）

本仓库基于 [Jcnet-9527/dsh-Jpet-phone](https://github.com/Jcnet-9527/dsh-Jpet-phone) **v0.6.3**（上游 commit `5dca11b`）定制，遵循上游 **MIT** 许可。

目标：在保留上游全部桌宠表现（动画、物理、音效、语音、多宠）的前提下，补上五件事——
**与 DSH 深度联动**、**息屏/开机后自动恢复**、**摇晃互动**、**动作台词与点击回应开关**、**扒在两侧**，
并让整条构建链能在 **ARM64 Linux（如 Termux/proot）** 上跑通。

---

## 一、与上游的差异（9 组）

### 1. 与 DSH 深度联动：余额 / 碎碎念 / 聊天
- 新增 `DshClient.kt`：用 DSH 启动 token 换 cookie，调用 dsh-pet 宿主暴露的 HTTP 路由
  - `POST /dsh-pet-7340/chat?pet=main&surface=any` → 带记忆的对话（用 DSH 里配的模型与人设）
  - `POST /dsh-pet-7340/whisper?pet=main&surface=any` → 一句碎碎念（模型生成）
  - `GET  /dsh-pet-7340/state` → 读取 `sections.balance.data` 里的余额
- `PetPreferences` 新增 `dsh / dshBaseUrl / dshToken`（默认 `http://127.0.0.1:3081`）
- `ChatModule`、`ChatActivity`、`SpeechModule` 在 `dsh` 开启时改走 DSH，否则保持上游的 OpenAI 兼容逻辑
- 设置页新增「联动 DSH」开关 + 地址 + token（`ui/ChatSection.kt`）

> 说明：token 每次 DSH 重启都会变，需在 App 内更新一次；地址与 DSH 同机时用 loopback 即可。

### 2. 息屏 / 开机后自动恢复
上游的问题是：服务返回 `START_NOT_STICKY`，且息屏时 `removeWindows()` 删窗口、亮屏重建可能失败，
导致解锁后桌宠消失、必须回 App 点启动。

修复：
- `PetOverlayService` 改为 `START_STICKY`，并持久化 `running` 标记；被系统重启时（intent == null）自动 `startPet()`
- **息屏不再删除窗口**，只暂停动画（窗口在屏幕关闭时几乎不耗电）
- 3 秒自愈轮询：`windows == 0` 或 `interactive` 状态变化时自动重建/恢复渲染
  （部分 ROM 不派发 `SCREEN_ON`/`USER_PRESENT` 给清单接收器，只能轮询）
- `WatchdogReceiver`：`AlarmManager.setAndAllowWhileIdle` 看门狗，息屏期间每 2 分钟自检，**进程被杀也能触发**
- `ScreenWakeReceiver`（清单注册，尽力而为）、`BootReceiver`（开机自启，仅在用户未手动停止时）

### 3. 摇晃互动（加速度计）
- 新增 `modules/ShakeModule.kt`：**不需要任何权限**，只用 `TYPE_ACCELEROMETER`
  - 低通去重力 + 高频能量阈值（≈0.2g，冷却 200ms）判定"摇晃"，避免正常走动误触
  - 检测到摇晃：给每只宠物注入冲量（复用上游 `nudge` 的碰撞/甩飞/边界逻辑）并触发 Q 弹
  - 持续倾斜：`PetEngine.tilt/tiltVelocity` 弹簧阻尼，`PetView.onDraw` 绕**底边中点**旋转 + 轻微横移
  - 仅在亮屏解锁时注册传感器，息屏/暂停自动注销
- `PetPreferences` 新增 `shake / shakeStrength`；设置页新增「摇晃互动」开关与强度滑杆（`ui/ShakeSection.kt`）

### 4. 动作台词 + 点击回应开关（上游 0.6.3 的「台词跟着动作走」）
- 素材清单新增 `line` 字段：`assets/characters/shenshen/videos/clip_lines.json` 给 10 个动作各配一句台词，
  `export_android_assets.py` 按「精确名 → `-后缀` → 包含」三级匹配写进每个 clip；台词是**「深深」口吻**（上游原文是「本鱼」）
- `PetEngine.clipStarts`：每次动画开始自增，服务据此判断"这一段要不要开口"（重播同一段也算一次）
- `PetOverlayService.sayClipLine()`：动作一开就上气泡；同句 10 秒内不重复、两条台词间隔 1.5 秒以上、时长按字数夹 3–8 秒
- **与上游不同：内置 5 句台词池保留**（上游 0.6.3 把它删空了）。DSH 碎碎念和用户自定义台词都靠这个池子；
  带台词的动作播放时池子自动让路，空闲动作照旧可能碎碎念
- 设置页新增「点它时说预设台词」开关（`PetOptions.clickReply`，默认开）：
  - 开：点击回应秒出动画自带的那句话，语音模式一起朗读
  - 关：交给 DSH 生成（`DshClient.whisper`），每次不同；没开 DSH 或请求失败时退回台词池
- 余额显示同步上游 0.6.3 修复（`balance_infos` 是数组，旧代码按对象读，永远显示"没给出数字"）

### 5. 构建链适配 ARM64 Linux / 老 aapt2
上游默认要求 `compileSdk 36`，而 aarch64 可用的 aapt2（Debian 2.19）读不了 API 35/36 的资源表：
- `app/build.gradle.kts`：`compileSdk/targetSdk` 34、依赖降到 compileSdk 34 可用的版本
  （Compose BOM 2024.06.00、activity-compose 1.9.0、core-ktx 1.13.1、coroutines 1.8.1）
- `gradle.properties`：`android.aapt2FromMavenOverride=/usr/bin/aapt2`（指向本机 aarch64 aapt2）
- `scripts/fetch_character_videos.py`：给中文文件名补 URL 编码（上游直接拼 URL 会抛 UnicodeEncodeError）
- `scripts/export_android_assets.py`：逐段串行转帧改为 `PET_EXPORT_JOBS` 并发（106 段从 ~2 小时降到 ~40 分钟）

> 若你在 x86_64 主机构建，可把 `compileSdk` 调回 36 并移除 aapt2 override。

### 6. 版本号与包名
- `applicationId` 改为 `io.github.dshpet.mobile.dsh`，可与上游正式版**并存安装**
- `versionName` 采用 `0.6.3-dsh.N`、`versionCode` 递增，避免每次安装提示"已安装相同版本"

### 7. 调试钩子（仅 debug 构建）
- `pet.SHAKE_TEST` 动作 + `MainActivity --ez shakeTest true`：注入一次模拟摇晃，便于自动化验证
- `pet.WALL_TEST` / `pet.WALL_TEST_RIGHT` + `MainActivity --ez wallTest true` / `--ez wallTestRight true`：
  让主宠物立刻挂到左墙 / 右墙，便于验证「扒在两侧」
- `files/pet-events.log`：息屏/亮屏/自愈/看门狗/注入等事件留痕，用于排查"桌宠不见了"
- 以上均由 `BuildConfig.DEBUG` 保护，**release 包不生效**

### 8. 第四个音色：melo（新增）
- 上游 3 个音色（甜甜 / 小雅 / 超文）之外，新增 `melo`（`TIMBRE_MELO`）：44.1 kHz、int8 量化（模型 53 MB + 词典 55 MB）、中英混读最自然
- 模型不在上游 assets 仓库，放在我们自己的 **`ranw524/dsh-Jpet-voice`**（3 个 18 MB 分卷，`voice/melo.zip.001..003`）；
  `VoiceModelSpec` 新增 `mirrors` 字段，让这个音色优先走自己的镜像（jsDelivr gcore/testingcf/cdn + raw + ghfast），不去撞上游的 404
- `VoiceEngine.speaker()` 改成按磁盘上实际存在的文件决定配置：matcha / piper-VITS / melo-VITS 三种形态共用一条路径，
  词典和 rule fsts 缺了就不传（缺文件传给 sherpa-onnx 是崩溃而不是异常）
- melo 原始输出峰值只有 0.13，比其他音色小一截：播放前乘 6 倍增益（`SherpaSpeaker(gain=…)`），并靠 clamp 防溢出
- melo 的 55 MB 词典首次解析约 20 秒：`VoiceModule.warmSpeaker()` 在语音模式打开时就后台预热，
  设置页选音色的提示里也写明了「约 20 秒准备」

### 9. 扒在两侧（探头）
- 不旋转整只宠物：保持直立，从地面沿屏幕左/右侧爬上去，**默认露出小半只身子（脑袋、一只手和一点围裙）**
  （身子在屏外，被系统裁掉；把身体放到负 x，窗口数学完全不用改）
- **露出多少可调**：设置 → 互动 →「露出多少」滑杆 30%–65%，默认 45%；
  往左拉到 30% 只剩脑袋，往右拉到 65% 能看见大半只
- 挂上去的高度在两侧的 18%–82% 里随机（按宠物自身高度收窄，竖屏/横屏都不会贴到地面），
  用「被鼠标拖拽悬空」动作当悬挂，以 60 源像素/秒慢慢爬到目标高度
- 默认每 4 分钟（±25% 抖动）自己贴一次；挂 30–90 秒后松手，并带一点向内的初速度掉回桌面，
  不会落在屏幕边缘卡半只身子
- 拖动即脱离（`beginDrag` 清空 `wallSide`），并重新计时，不会马上又贴回去；
  挂墙上点击照常播点击回应/台词；多宠（小鲸鱼）不参与
- 挂墙期间「按住说话」按钮自动收起（它跟随宠物，会有一半挂在屏幕外）；气泡仍夹在本屏内
- 设置：互动 →「扒在两侧」开关 +「多久去一次」滑杆（1–15 分钟，默认 4）+「露出多少」滑杆
- 实现：`PetEngine.wallSide/advanceWall()` 走独立时钟（无重力、不游走）；
  `PetOverlayService.maybeWall()` 定时、`enterWall()` 算探出宽度的 x 并记录松手时间
- 调试：`--ez wallTest true` 挂左墙、`--ez wallTestRight true` 挂右墙（仅 debug 构建）

---

## 二、构建

标准环境（x86_64 + Android SDK 36）：

```bash
cd android-app
python3 -m pip install pillow
python3 scripts/fetch_character_videos.py      # 拉取 52MB 源视频
cd android
./gradlew :app:assembleDebug -PpetPython=python3
```

本仓库当前为 **ARM64 Linux** 适配的配置（见差异 5），我这边实际使用的流程：

```bash
cd android-app
python3 -m pip install pillow PySide6 imageio-ffmpeg
python3 scripts/fetch_character_videos.py
cd android
/opt/gradle-8.13/bin/gradle :app:assembleDebug -PpetPython=python3
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

> 构建脚本会调用 `scripts/export_android_assets.py` 把 webm 逐帧转成无损 webp，
> 首次构建较慢（本项目 106 段 / 25423 帧）；之后走缓存。
> `android-app/android/local.properties`（`sdk.dir=...`）请勿提交。

---

## 三、版本历史

| 版本 | 内容 |
|---|---|
| `0.6.2-dsh.10` | 首个可安装版本；DSH 联动（chat/whisper/balance）跑通 |
| `0.6.2-dsh.11` | 息屏不删窗口 + 3 秒自愈；修复"解锁后桌宠消失" |
| `0.6.2-dsh.12` | 解锁状态轮询，修复"开机后静态、需手动点启动" |
| `0.6.2-dsh.13` | 摇晃互动首版（加速度计 + 弹簧倾斜 + 冲量 + 设置项） |
| `0.6.2-dsh.14` ~ `.17` | 摇晃注入/调试钩子、版本号自增机制、传感器接线验证 |
| `0.6.2-dsh.18` | 摇晃手感调优：阈值 3.4→2.0、倾斜增益 0.55→1.05、冲量 90+260s→160+560s |
| `0.6.2-dsh.19` | 窗口跟随当前动画包围盒，不再吞掉其他 App 的点击；新增「点击穿透」开关 |
| `0.6.3-dsh.20` | 合并上游 0.6.3：动作台词（10 句「深深」口吻）+ 点击回应开关 + 余额修复；内置台词池保留 |
| `0.6.3-dsh.21` | 新增第 4 个音色 **melo**（44.1k、int8、中英混读最强）；自带镜像 + 复用音箱预热 |
| `0.6.3-dsh.31` | 新增「扒在两侧」：隔 1–15 分钟从底部爬上左/右侧，默认露出小半只身子，挂 30–90 秒再掉回桌面；露出比例可调 |

详细文件级改动见 [`docs/CHANGES-vs-upstream.md`](docs/CHANGES-vs-upstream.md)。

---

## 四、许可与上游

- 本仓库继承上游 **MIT**；请保留 `LICENSE`、`android-app/LICENSE-APACHE-2.0.txt`、`android-app/NOTICE.txt`、`android-app/THIRD_PARTY_NOTICES.md`。
- 上游 0.6.3 的**动作台词**与**余额修复**已合并进本仓库；**内置更新检测**与**小鲸鱼人设**按计划不采用
  （更新检测硬编码上游仓库、且我们的版本号形如 `0.6.3-dsh.N`，照搬会一直误报新版本；人设保留「深深」）。
  `UpdateChecker.kt` 目前是未接线的死代码，留待以后接我们自己的 release 通道。
- 合并上游时注意本仓库差异 5 的构建配置（compileSdk/aapt2 override）会被覆盖。
