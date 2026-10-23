# 文件级改动（相对上游 8e39347 / v0.6.2）

> 完整补丁：[`changes.patch`](changes.patch)

## 新增文件

| 文件 | 作用 |
|---|---|
| `android-app/android/app/src/main/java/io/github/dshpet/mobile/DshClient.kt` | 与 DSH 宿主通信：token→cookie，`chat` / `whisper` / `state(余额)` |
| `.../modules/ShakeModule.kt` | 加速度计摇晃检测 + 弹簧倾斜 + 冲量 + 传感器生命周期 |
| `.../ui/ShakeSection.kt` | 设置页「摇晃互动」开关与强度滑杆 |
| `.../WatchdogReceiver.kt` | AlarmManager 看门狗（进程被杀也能在亮屏时恢复） |
| `.../ScreenWakeReceiver.kt` | 清单注册的亮屏/解锁接收器（尽力而为） |
| `.../BootReceiver.kt` | 开机自启（仅当用户未手动停止） |

## 修改文件

| 文件 | 改动要点 |
|---|---|
| `AndroidManifest.xml` | 新增 `RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；注册三个 receiver |
| `PetOverlayService.kt` | `START_STICKY`；null-intent 自动恢复；息屏不删窗口；3s 自愈；事件日志；`SHAKE_TEST`（debug）；注册 ShakeModule |
| `PetPreferences.kt` | 新增 `dsh/dshBaseUrl/dshToken`、`shake/shakeStrength`、`running` 标记与 `log()` |
| `PetEngine.kt` | 新增 `tilt` / `tiltVelocity`（供摇晃渲染） |
| `PetView.kt` | `onDraw` 绕底边中点旋转 + 横移（倾斜渲染） |
| `ChatModule.kt` / `ChatActivity.kt` / `SpeechModule.kt` | DSH 开启时改走 `DshClient`（聊天/余额/碎碎念） |
| `ui/ChatSection.kt` | 设置页：联动 DSH 开关 + 地址 + token |
| `MainActivity.kt` | 「允许后台常驻」按钮；`shakeTest` 调试钩子；引入 ShakeSection |
| `ModuleIds.kt` | 新增 `SHAKE` |
| `res/values/strings.xml` | 中英文案：DSH 联动、摇晃互动、后台常驻 |
| `app/build.gradle.kts` | compileSdk/targetSdk 34、buildTools 29.0.3、依赖降级、applicationId `.dsh`、版本号 |
| `android/gradle.properties` | `android.aapt2FromMavenOverride=/usr/bin/aapt2` |
| `scripts/fetch_character_videos.py` | 中文文件名 URL 编码修复 |
| `scripts/export_android_assets.py` | 并发转帧（`PET_EXPORT_JOBS`） |
