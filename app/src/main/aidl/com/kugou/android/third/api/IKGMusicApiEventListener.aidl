package com.kugou.android.third.api;

import android.os.Bundle;

interface IKGMusicApiEventListener {
    // tx 1
    void onEvent(String event, in Bundle data);
}
