package io.github.lmk26.hypersliderguard

import android.app.Application

class HyperSliderGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogManager.i(TAG, "HyperSliderGuardApp 启动: version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        XposedServiceManager.init()
    }

    companion object {
        private const val TAG = "HyperSliderGuard"
    }
}