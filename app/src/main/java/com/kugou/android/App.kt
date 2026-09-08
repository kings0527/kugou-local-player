package com.kugou.android

import android.app.Application

/** 应用入口 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.init(this)
    }
}

object AppCtx {
    lateinit var app: Application
        private set
    fun init(a: Application) {
        app = a
    }
}
