package com.kugou.android.thirdmap;

import android.os.Bundle;

interface IKGMapApiEventListener {
    // tx 1
    void onEvent(String event, in Bundle data);
}
