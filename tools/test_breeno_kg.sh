#!/bin/bash
# 模拟 OPPO 小布(Breeno) 绑定酷狗音乐服务的调用序列
export PATH="$PATH:/Users/kk/Library/Android/sdk/platform-tools"
D=3B164Q009U000000
PKG=com.kugou.android
ACTION=com.kugou.android.third.api.KGMusicApiService

echo "=== 1. 检查服务可被解析（小布能否 bind） ==="
adb -s $D shell "cmd package resolve-activity --brief -a $ACTION -p $PKG" 2>&1 | tail -2

echo "=== 2. 检查服务是否 exported ==="
adb -s $D shell "dumpsys package $PKG | grep -A4 'KGMusicApiService' | head -8"

echo "=== 3. 触发绑定（用小布同样的 action） ==="
adb -s $D logcat -c
adb -s $D shell "am startservice -a $ACTION -n $PKG/.thirdapi.KGMusicApiService" 2>&1 | tail -1
sleep 3
adb -s $D logcat -d 2>/dev/null | grep -iE "KGMusicApi|kugou.android.third" | tail -10
