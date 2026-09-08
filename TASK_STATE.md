# 乐聆/酷狗音乐 checkpoint 8 — 语音助手终于打通！✅
## 决定性突破（2026-09-09 01:0x）
小布(Breeno) 已能成功调用本应用：**播放/暂停/下一首/上一首全部生效，且可连续执行**

### 根因链条（全部已验证）
1. 小布音乐通路工厂 `AIChatMusicController.w(pkg)`（dex line 15931729）：
   - pkg == com.kugou.android 且 `KuGouHelper.b(ctx)` 为真（**versionCode >= 20169**）→ 返回 "newkugoumusic"
   - 否则返回 null → 提示「该应用暂不支持此操作」
   - 另有 `KuGouHelper.a(ctx)`：versionCode >= 10008 → "kugoumusic"（旧 AIDL 通路）
2. 新版通路协议（逆向 `com.kugou.kgmusicaidlcop.KGEngine`）：
   - bind action = `com.kugou.android.thirdmap.KGMusicUnityService`, pkg com.kugou.android
   - intent extra `sdk_channel` = 调用方包名
   - AIDL = `com.kugou.android.thirdmap.IKGMapApi`（execute/executeAsync/registerEventListener/unregisterEventListener，**executeAsync 无 callbackId 参数**）
   - 授权：`v2_check_authority{openAppId=10272, secretKey=uvrMziPtvkZueI1F6smPOa9HWxYQNwHG, packageName}` → code=0
   - 查询：v1_getCurrentSong / v1_getCurrTime / v1_getTotalTime / v1_get_curr_real_playing_data / v1_query_play_mode / v1_is_queue_reloaded / v1_action_play_index
   - **命令入口：`kgSemanticSlots`**，参数 `semanticslots` 是 JSON 字符串：
     `{"args":null,"domain":"music","intent":"next","query":null,"slots":null,"type":2}`
     intent 取值 next/prev/pause/play/resume/changePlayMode/search
   - 事件：API_EVENT_PLAY_SERVICE_INITIALIZED / PLAY_STATE_CHANGED{playState} / PLAY_SONG_CHANGED
   - 状态码：4=播放中（小布判定）
3. 关键坑（已修）：
   - versionCode 必须 >= 20169（原 1 → 9259 → 20169）
   - `kgSemanticSlots` 的真实意图在 JSON 的 intent 字段（不是 query）→ 必须解析 JSON
   - ExoPlayer 必须在主线程：AIDL 回调在 Binder 线程 → onMain{} 切线程（否则 crash）
   - 事件上报延迟 200ms（小布需先完成 register 回执）
4. 已实现 3 个服务（Manifest 全部注册，系统可 resolve）：
   - .thirdapi.KGMusicUnityService ← com.kugou.android.thirdmap.KGMusicUnityService（★主用）
   - .thirdapi.KGMusicApiService ← com.kugou.android.third.api.KGMusicApiService（旧通路）
   - .thirdapi.CMApiService ← com.netease.cloudmusic.third.api.CMApiService（网易云，需 token，保留）
5. deeplink 通路（versionCode >= 9259）也已实现：
   - `kugou://m.kugou.com/voicehelper?query=下一首&appid=3098...`（KuGouDeepLinkPlayer）
   - MainActivity.handleIntent 解析 query：播放/暂停/下一首/上一首/随机/单曲循环/顺序/搜索

### 真机验证（OPPO PME110 A16）
- 自测（模拟小布全流程）：v2_check_authority→0, registerEventListener→0, kgSemanticSlots(next)→切歌成功
- 连续 3 次 next：归来吧 → 当爱已成往事 → 月亮 ✅
- 用户实测：下一首生效 ✅（此前"不能连续"是测试工具问题：singleTask Activity 的 onNewIntent 未处理 selftest，已修）

### 工程状态
- 包名/namespace = com.kugou.android，应用名"酷狗音乐"，versionCode=20169 / versionName=10.2.69
- 其余功能（扫描/EQ/文件夹/忽略目录/封面/insets）此前已验证

## 用户剩余要求
1. git 提交 + 推送 + README + 介绍 + 为什么用酷狗名字（未做）
2. 图标重做（用户说太丑）（未做）
3. 音频焦点/来电/其他 app 抢占审计（ExoPlayer 已配 handleAudioFocus=true + becomingNoisy，待实测）
