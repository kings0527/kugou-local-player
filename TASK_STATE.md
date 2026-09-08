# 乐聆 checkpoint 3 (构建/真机状态)
见 TASK_STATE.md。构建+真机验证已基本全通：

## 已验证 (OPPO PME110 A16, adb -s 3B164Q009U000000)
1. ✅ 编译通过: gradle :app:assembleDebug (BUILD SUCCESSFUL, ~10.5MB debug apk)
2. ✅ 安装成功 (首次 -99 失败, 重试 Success)
3. ✅ 启动无崩溃: MainActivity 前台
4. ✅ 歌曲扫描: 1505 首 (kgmusic 1402 + Music 97 等; MediaStore 全量)
5. ✅ 文件夹页 + 目录列表正常
6. ✅ 播放链路 (bindService 修复后): 点歌 → PLAYING, position 走, buffered 78s, 无 FGS 崩溃
7. ✅ insets 修复: 顶部标题 y37→y157 (避开状态栏 120px); 底部手势导航(高0)无需处理
8. ❌ 通知权限: importance=NONE (OPPO 默认拒) → 已改 MainActivity.maybeFirstScan 申请 POST_NOTIFICATIONS (Android13+ 与 READ_MEDIA_AUDIO 一起), 待验证

## 已修关键 bug (历史)
- LibraryRepo 回调跑 Default 线程碰 UI → main.post 包 onDone
- startForegroundService 5s 未 startForeground → ForegroundServiceDidNotStartInTimeException 崩溃 → 改 bindService(BIND_AUTO_CREATE) 拉起, Media3 播时自动前台化
- EqualizerPresets Short/Int API 错; audioSessionId SDK36 移除 → eqSessionId 自跟踪(onAudioSessionIdChanged)
- SongAdapter/FolderAdapter bindingAdapterPosition → tag
- MainActivity MEDIA_PLAY_FROM_URI 常量不存在用字面量
- Perm.kt 扩展/imports; artLetter 移到 util
- 动态 MaterialButton 改 XML eq0..eq8
- FoldersFragment nullable dir + R import
- miniPlayer 绑定是 ViewMiniPlayerBinding(.root); renderMini 用 b.miniPlayer.root

## 当前文件位置
工程: /Users/kk/work/music-app (非git repo)
APK: app/build/outputs/apk/debug/app-debug.apk
日志: adb logcat -d -b crash / grep leling

## 下一步测试
1. 重编+安装 → 首次启动应弹 READ_MEDIA_AUDIO+POST_NOTIFICATIONS 双权限 (授予)
2. 点歌验证通知栏媒体卡片出现 (dumpsys notification | grep leling)
3. EQ 测试: 设置页开 EQ 选预设 → 播放中不崩
4. 随机播放按钮 / 循环模式切换验证
5. 播放页 PlayerActivity 布局/seek
6. 语音助手: Breeno 命令测试 (可选, 需用户配合) — intent-filter 已声明 MEDIA_PLAY_FROM_SEARCH
7. 可考虑 release 签名包 / git init
