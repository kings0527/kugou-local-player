package com.netease.cloudmusic.third.api.contract;

import android.os.Bundle;

interface ICMAipEventListener {
    // tx 1
    List<String> events();
    // tx 2
    void onEvent(String event, in Bundle data);
}
