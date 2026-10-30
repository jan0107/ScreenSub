// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app

import android.app.Application
import com.screensub.core.common.Log

class ScreenSubApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Log.enabled = BuildConfig.DEBUG

        // 文件日志由设置里的「详细日志」开关控制（见 MainActivity），
        // 这里不再无条件挂载：用户没开就不写，开了关闭时还会删掉文件。
        Log.i("App", "ScreenSub 启动，debug=${BuildConfig.DEBUG}")
    }
}
