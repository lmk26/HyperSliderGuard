package io.github.lmk26.hypersliderguard

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object XposedServiceManager {

    private const val TAG = "HyperSliderGuard"

    private val initialized = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

    @Volatile
    var currentService: XposedService? = null
        private set

    fun init() {
        if (!initialized.compareAndSet(false, true)) {
            return
        }
        LogManager.i(TAG, "注册 XposedServiceHelper 全局监听")
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                currentService = service
                val info = runCatching {
                    "${service.frameworkName} ${service.frameworkVersion} (api=${service.apiVersion}, scope=${service.scope})"
                }.getOrElse { "未知框架信息: ${it.message}" }
                LogManager.i(TAG, "XposedService 已绑定: $info")
                notifyListeners(service)
            }

            override fun onServiceDied(service: XposedService) {
                currentService = null
                LogManager.w(TAG, "XposedService 连接断开")
                notifyListeners(null)
            }
        })
    }

    fun addListener(listener: (XposedService?) -> Unit) {
        listeners.add(listener)
        currentService?.let { listener(it) }
    }

    fun removeListener(listener: (XposedService?) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(service: XposedService?) {
        for (listener in listeners) {
            listener(service)
        }
    }
}