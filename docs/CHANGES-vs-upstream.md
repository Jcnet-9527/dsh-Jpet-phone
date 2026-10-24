# 文件级改动（相对上游 5dca11b / v0.6.3）

> 完整补丁：[`changes.patch`](changes.patch)（0.6.2 时代的快照，已过期，仅供参考）

## 新增文件

| 文件 | 作用 |
|---|---|
| `android-app/android/app/src/main/java/io/github/dshpet/mobile/DshClient.kt` | 与 DSH 宿主通信：token→cookie，`chat` / `whisper` / `state(余额)` |
| `.../modules/ShakeModule.kt` | 加速度计摇晃检测 + 弹簧倾斜 + 冲量 + 传感器生命周期 |
| `.../ui/ShakeSection.kt` | 设置页「摇晃互动」开关与强度滑杆 |
| `.../WatchdogReceiver.kt` | AlarmManager 看门狗（进程被杀也能在亮屏时恢复） |
| `.../ScreenWakeReceiver.kt` | 清单注册的亮屏/解锁接收器（尽力而为） |
| `.../BootReceiver.kt` | 开机自启（仅当用户未手动停止） |
| `android-app/assets/characters/shenshen/videos/clip_lines.json` | 10 个动作的台词（「深深」口吻；上游原文是「本鱼」） |

## 修改文件

| 文件 | 改动要点 |
|---|---|
| `AndroidManifest.xml` | 新增 `RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；注册三个 receiver |
| `PetOverlayService.kt` | `START_STICKY`；null-intent 自动恢复；息屏不删窗口；3s 自愈；事件日志；`SHAKE_TEST`（debug）；注册 ShakeModule；`sayClipLine()`（动作台词上气泡，同句 10s 去重、间隔 1.5s） |
| `PetPreferences.kt` | 新增 `dsh/dshBaseUrl/dshToken`、`shake/shakeStrength`、`clickReply`（`ClickReply.LINE/DSH`）、`running` 标记与 `log()`；`clipLineActive()` 统一判断"这句台词此刻该不该说" |
| `PetEngine.kt` | 新增 `tilt` / `tiltVelocity`（供摇晃渲染）；`clipStarts`（每次动画开始自增，供台词判定） |
| `PetView.kt` | `onDraw` 绕底边中点旋转 + 横移（倾斜渲染） |
| `ChatModule.kt` / `ChatActivity.kt` / `SpeechModule.kt` | DSH 开启时改走 `DshClient`（聊天/余额/碎碎念）；`SpeechModule` 在带台词的动作播放时让路（点击仍走 DSH 时不让） |
| `ui/ChatSection.kt` | 设置页：联动 DSH 开关 + 地址 + token |
| `MainActivity.kt` | 「允许后台常驻」按钮；`shakeTest` 调试钩子；引入 ShakeSection；把 `lineCount` 传给语音设置 |
| `ModuleIds.kt` | 新增 `SHAKE` |
| `res/values/strings.xml` / `values-en` | 中英文案：DSH 联动、摇晃互动、后台常驻、点击回应开关、台词提示 |
| `app/build.gradle.kts` | compileSdk/targetSdk 34、buildTools 29.0.3、依赖降级、applicationId `.dsh`、版本号 |
| `android/gradle.properties` | `android.aapt2FromMavenOverride=/usr/bin/aapt2` |
| `scripts/fetch_character_videos.py` | 中文文件名 URL 编码修复 |
| `scripts/export_android_assets.py` | 并发转帧（`PET_EXPORT_JOBS`）；读 `clip_lines.json` 把 `line` 写进每个 clip（**保留**上游已删的内置台词池） |
| `android/app/src/test/.../PetOptionsTest.kt` | 跟上本仓库新增的 SHAKE 模块（上游测试早于它） |

## 上游 0.6.3 里**不采用**的部分

| 上游改动 | 原因 |
|---|---|
| 内置更新检测（`UpdateChecker` 接线到设置页） | 硬编码 `Jcnet-9527/dsh-Jpet-phone` releases，我们的版本是 `0.6.3-dsh.N`，照搬会一直误报；点"立即更新"装的是上游包名（`io.github.dshpet.mobile`）的另一个 App |
| 小鲸鱼人设 | 本仓库保留「深深」人设；聊天默认走 DSH 时人设本来由 DSH 侧决定 |
| 删除内置 5 句台词池 | 我们的 DSH 碎碎念与自定义台词依赖它 |
