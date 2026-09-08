// 酷狗音乐第三方控制 API —— OPPO 小布(Breeno) 语音播放的无 token 通路
// 方法顺序/签名须与 com.heytap.speechassist 中一致（事务码由顺序决定）
package com.kugou.android.third.api;

import android.os.Bundle;
import com.kugou.android.third.api.IKGMusicApiCallback;
import com.kugou.android.third.api.IKGMusicApiEventListener;

interface IKGMusicApi {
    // tx 1
    Bundle execute(String cmd, in Bundle params);
    // tx 2
    void executeAsync(String cmd, in Bundle params, IKGMusicApiCallback callback);
    // tx 3
    Bundle registerEventListener(in List<String> events, IKGMusicApiEventListener listener);
    // tx 4
    Bundle unregisterEventListener(in List<String> events, IKGMusicApiEventListener listener);
}
