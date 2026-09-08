# 乐聆 LeLing Music 🎵

纯本地音乐播放器 —— 无广告、无网络、超省电。

自研原创应用（Kotlin + AndroidX Media3/ExoPlayer），参考了 GitHub 高星项目
（RetroMusicPlayer / Auxio / Vinyl）的功能模型，未使用其任何代码。

## 功能

| 功能 | 说明 |
|---|---|
| 本地音乐播放 | 系统 MediaStore 扫描全部本地音频，不联网、无广告 |
| 随机 / 列表循环(自动连播) / 单曲循环 | 播放页一键切换，设置持久化 |
| 文件夹浏览 | 按目录树展示，可点开目录播放 |
| 自动扫描 | 启动/手动刷新即扫描，纯内存缓存，快速高效 |
| 忽略目录 | 文件夹长按/弹窗可"忽略此目录"（含子目录），黑名单存储 |
| 只听模式 | 白名单：只播放勾选的文件夹（在设置里切换） |
| 忽略系统目录 | 默认自动跳过 闹钟/通知/铃声/录音 目录（可关） |
| 常用 EQ 预设 | 标准/流行/摇滚/爵士/古典/舞曲/电子/低音增强/人声，播放中才启用（省电） |
| 系统媒体集成 | 通知栏媒体卡片、锁屏控制、蓝牙线控、可设为系统默认播放器 |
| 语音助手 | 支持 "播放音乐 / 播放XX / 随机播放" 等系统语音指令（需系统授权默认播放器） |

## 技术要点

- **省电**：播放中才以前台服务 + 启用 EQ；暂停即释放；拔耳机自动暂停（ExoPlayer）
- **高效**：MediaStore 一次扫描入内存，过滤纯内存运算；UI 观察 StateFlow
- **架构**：`PlayerHub`(播放中枢) — Media3 MediaSessionService(ExoPlayer) + SettingsRepo(DataStore) + LibraryRepo(扫描缓存)
- 最小 API 26 (Android 8.0)，targetSdk 36

## 构建

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 已知系统差异

- OPPO/ColorOS 通知权限需用户手动允许（首次启动会请求）；shell adb 无法代授
- Android 15+ 强制 edge-to-edge 已做 insets 适配
