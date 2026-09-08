# 酷狗音乐 · 本地播放器

> 一个**纯本地、无广告、无联网**的 Android 音乐播放器。
> 支持 OPPO 小布（Breeno）语音控制：**播放音乐 / 下一首 / 暂停 / 播放周杰伦的七里香**。

---

## 为什么叫「酷狗音乐」？

这不是山寨，而是**技术必需**。原因是一条完整的逆向分析链：

OPPO 小布语音助手（`com.heytap.speechassist`）在处理「播放音乐」这类指令时，
**不会**走 Android 标准的 `MEDIA_PLAY_FROM_SEARCH` intent，而是使用一套**私有协议**。
我们反编译小布 12.9.9 后发现，它内置了 **4 条第三方音乐通路**：

| 通路 | 绑定服务 | 是否需要服务端签名 |
|---|---|---|
| 网易云音乐 | `com.netease.cloudmusic.third.api.CMApiService` | ✅ 需要（RSA 签名，密钥不配对，**无法本地伪造**） |
| OPPO 音乐 | `com.oplus.music.playbackservice` | ❌ 不需要，但包名 `com.heytap.music` 被系统 sharedUserId 占用，**装不上** |
| **酷狗音乐** | `com.kugou.android.thirdmap.KGMusicUnityService` | ❌ **不需要，且包名可自由使用** |
| QQ 音乐 | `com.tencent.qqmusic.third.api.QQMusicApiService` | — |

并且小布的选择逻辑是硬编码的（`AIChatMusicController.w(pkg)`）：

```java
if (pkg.equals("com.kugou.android") && KuGouHelper.b(ctx))  // versionCode >= 20169
    return "newkugoumusic";     // ✅ 走我们的服务
return null;                     // ❌ 提示「该应用暂不支持此操作」
```

**结论**：要让小布能语音控制本应用，必须满足三个条件——
① 包名 = `com.kugou.android`；② versionCode ≥ 20169；③ 实现酷狗的 AIDL 服务。

所以这个应用叫「酷狗音乐」、包名 `com.kugou.android`、versionCode `20169`，
是**为了接入系统语音助手的唯一可行路径**，而非冒充商业软件。
它本身是 100% 原创代码，不联网、不收集数据、无广告。

---

## 功能

| 功能 | 说明 |
|---|---|
| 本地音乐播放 | 系统 MediaStore 扫描，不联网 |
| **语音助手** | 小布「播放音乐 / 下一首 / 上一首 / 暂停 / 播放周杰伦的七里香」 |
| 随机 / 列表循环 / 单曲循环 | 播放页一键切换，持久化 |
| 文件夹浏览 | 目录树展示，可点开目录播放 |
| 忽略目录 | 长按文件夹「忽略此目录」（含子目录） |
| 忽略系统目录 | 默认跳过 闹钟/通知/铃声/录音 |
| EQ 预设 | 标准/流行/摇滚/爵士/古典/舞曲/电子/低音增强/人声（播放中才启用，省电） |
| 专辑封面 | 真实封面 + 旋转唱片动画，支持上下滑动切歌 |
| 系统媒体集成 | 通知栏卡片、锁屏控制、蓝牙线控、可设为默认播放器 |
| 音频焦点 | 来电/其他应用播放时自动暂停，拔耳机自动暂停 |
| 坏文件跳过 | 加密格式（.kgm/.kgg）或损坏文件自动跳过，不卡死 |

---

## 语音控制支持的命令

对小布说：

- 「播放音乐」→ 从头播放全部
- 「随机播放」→ 随机播放
- 「下一首」/「上一首」
- 「暂停」/「播放」（继续）
- 「单曲循环」/「顺序播放」
- 「播放周杰伦的七里香」→ 按歌手+歌名精确搜索

---

## 技术架构

```
MainActivity ──┬── SongsFragment    歌曲列表
               ├── FoldersFragment  文件夹浏览
               └── SettingsFragment 设置（EQ / 忽略目录 / 默认播放器）
PlayerActivity  播放页（旋转唱片 / 封面 / 滑动切歌）

PlayerHub       播放中枢（单例）
  ├── ExoPlayer + MediaSession（PlayerService 前台服务）
  ├── 音频焦点 / 拔耳机暂停 / 错误自动跳过
  └── 语音服务绑定

语音服务（三套，均为逆向实现的 AIDL）
  ├── KGMusicUnityService   ← com.kugou.android.thirdmap.KGMusicUnityService  ★主用
  ├── KGMusicApiService     ← com.kugou.android.third.api.KGMusicApiService
  └── CMApiService          ← com.netease.cloudmusic.third.api.CMApiService

数据层
  ├── LibraryRepo    MediaStore 扫描 + 过滤缓存
  └── SettingsRepo   DataStore 持久化
```

### 语音协议关键细节

小布通过 `kgSemanticSlots` 下发意图，真实命令在 JSON 里：

```json
{"intent":"next"}                                   // 下一首
{"intent":"play","query":"播放周杰伦的七里香",
 "slots":[{"name":"singer","values":[{"text":"周杰伦"}]},
          {"name":"song","values":[{"text":"七里香"}]}]}   // 点歌
```

状态回传用 `API_EVENT_PLAY_STATE_CHANGED`（`playState=4` 表示播放中）。

---

## 构建

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

环境：Android SDK 36 / JDK 17 / Gradle 8.14 / Kotlin 2.1 / minSdk 26

---

## 声明

- 代码 100% 原创，未使用任何开源播放器的源代码（仅参考了功能设计）
- 不联网、无广告、无数据收集
- 包名与 versionCode 对齐酷狗是为了接入 OPPO 语音助手，不含任何酷狗商业代码
- 图标：原创矢量图
