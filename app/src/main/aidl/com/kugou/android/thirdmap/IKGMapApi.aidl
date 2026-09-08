// 酷狗「音乐统一服务」API（新版，KGMusicUnityService）
// OPPO 小布(Breeno) 在酷狗 versionCode >= 20169 时使用此通路
// 方法顺序须与 com.heytap.speechassist 中的 IKGMapApi 一致（事务码由顺序决定）
package com.kugou.android.thirdmap;

import android.os.Bundle;
import com.kugou.android.thirdmap.IKGMapApiCallback;
import com.kugou.android.thirdmap.IKGMapApiEventListener;

interface IKGMapApi {
    // tx 1
    Bundle execute(String cmd, in Bundle params);
    // tx 2（注意：无 callbackId 参数）
    void executeAsync(String cmd, in Bundle params, IKGMapApiCallback callback);
    // tx 3
    Bundle registerEventListener(in List<String> events, IKGMapApiEventListener listener);
    // tx 4
    Bundle unregisterEventListener(in List<String> events, IKGMapApiEventListener listener);
}
