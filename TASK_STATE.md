# 项目完成状态 (2026-09-09)
仓库: https://github.com/kings0527/kugou-local-player (PUBLIC)
APK: app/build/outputs/apk/debug/app-debug.apk (~10.6MB)
设备: OPPO PME110 Android 16 (adb -s 3B164Q009U000000)

## ✅ 全部功能验证通过
| 功能 | 状态 |
|---|---|
| 本地扫描 | 1505 首 (MediaStore) |
| 播放/暂停/下一首/上一首 | ✅ 真机验证 |
| 语音助手(小布) | ✅ 播放/暂停/恢复/下一首/上一首/点歌，可连续 |
| 点歌 | ✅ "播放周杰伦的惊叹号" → 精确匹配 |
| 随机/列表循环/单曲循环 | ✅ |
| 文件夹浏览/忽略目录 | ✅ |
| EQ 9 预设 | ✅ ChipGroup 自动换行 |
| 专辑封面 + 旋转唱片 | ✅ 真实封面 |
| 音频焦点 | ✅ 来电/其他 app 抢占自动暂停 |
| 抢占后自动恢复 | ✅ 抖音退出后恢复（用户暂停则不恢复） |
| 坏文件跳过 | ✅ 自动切下一首 |
| insets | ✅ 避开状态栏 |
| 0 崩溃 | ✅ |

## 语音控制关键实现（逆向 OPPO 小布 12.9.9）
必须满足三条件才能被小布识别：
1. 包名 = com.kugou.android
2. versionCode >= 20169
3. 实现 com.kugou.android.thirdmap.KGMusicUnityService (IKGMapApi AIDL)

命令入口: kgSemanticSlots，参数 semanticslots JSON:
- 控制: {"intent":"next"|"pause"|"play"|"prev"}
- 点歌: {"intent":"play","query":"...","slots":[{name:singer},{name:song}]}
- 状态回传: API_EVENT_PLAY_STATE_CHANGED{playState:4=播放中}

坑: Android org.json optString() 对 JSON null 返回字符串 "null"（非空）→ 必须过滤

## 三个语音服务（Manifest 全部注册）
- KGMusicUnityService  ← com.kugou.android.thirdmap.KGMusicUnityService ★主用
- KGMusicApiService    ← com.kugou.android.third.api.KGMusicApiService
- CMApiService         ← com.netease.cloudmusic.third.api.CMApiService

## 已交付
- README.md（含"为什么用酷狗名字"的完整技术说明）
- git 4 次提交，已推送公开仓库
- 图标重做（音符+声波，蓝渐变）
