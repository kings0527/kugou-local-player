// 网易云音乐第三方音乐控制 API —— 与 OPPO Breeno(小布) 交互的 AIDL 契约
// 接口名/方法顺序必须与 com.heytap.speechassist 中定义完全一致（事务码由顺序决定）
package com.netease.cloudmusic.third.api.contract;

import android.os.Bundle;
import com.netease.cloudmusic.third.api.contract.ICMAipCallback;
import com.netease.cloudmusic.third.api.contract.ICMAipEventListener;

interface ICMAip {
    // tx 1
    Bundle execute(String method, in Bundle params);
    // tx 2
    void executeAsync(String method, String callbackId, in Bundle params, ICMAipCallback callback);
    // tx 3
    Bundle registerEventListener(ICMAipEventListener listener);
    // tx 4
    Bundle unregisterEventListener(ICMAipEventListener listener);
}
