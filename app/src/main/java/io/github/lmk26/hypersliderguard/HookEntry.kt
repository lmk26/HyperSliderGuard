package io.github.lmk26.hypersliderguard

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

class HookEntry : IXposedHookLoadPackage {

    private enum class SliderType {
        BRIGHTNESS,
        VOLUME
    }

    private class CustomLongPressState {
        val handler = Handler(Looper.getMainLooper())
        var pendingRunnable: Runnable? = null
        var downRawX: Float = 0f
        var downRawY: Float = 0f
        var touchSlop: Int = 0

        fun cancel() {
            pendingRunnable?.let {
                handler.removeCallbacks(it)
                pendingRunnable = null
            }
        }
    }

    private val hookedClassLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
    private val sliderTypes = Collections.synchronizedMap(WeakHashMap<View, SliderType>())
    private val customStates = Collections.synchronizedMap(WeakHashMap<Any, CustomLongPressState>())
    private val viewPerformLongClickHooked = AtomicBoolean(false)

    @Volatile
    private var prefs: XSharedPreferences? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            XposedHelpers.findAndHookMethod(
                ModuleStatus::class.java.name,
                lpparam.classLoader,
                "isActivated",
                XC_MethodReplacement.returnConstant(true)
            )
            return
        }

        if (lpparam.packageName != SYSTEM_UI_PACKAGE) {
            return
        }

        prefs = XSharedPreferences(BuildConfig.APPLICATION_ID, PrefsConfig.PREFS_NAME).apply {
            makeWorldReadable()
        }
        XposedBridge.log("$TAG: [Legacy] handleLoadPackage 进入 ${lpparam.packageName}")

        hookViewPerformLongClickOnce()
        tryHookPlugin(lpparam.classLoader, "appClassLoader")

        XposedBridge.hookAllConstructors(
            BaseDexClassLoader::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dexPath = param.args?.firstOrNull() as? String ?: return
                    if (!dexPath.contains(PLUGIN_PACKAGE_KEYWORD)) {
                        return
                    }
                    val cl = param.thisObject as? ClassLoader ?: return
                    XposedBridge.log("$TAG: [Legacy] 捕获插件 ClassLoader: dexPath=$dexPath")
                    tryHookPlugin(cl, "pluginDexClassLoader")
                }
            }
        )
    }

    private fun hookViewPerformLongClickOnce() {
        if (!viewPerformLongClickHooked.compareAndSet(false, true)) {
            return
        }
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "performLongClick",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val type = sliderTypes[view] ?: return
                    if (getMode(type) == PrefsConfig.MODE_DISABLE) {
                        XposedBridge.log("$TAG: [Legacy] View.performLongClick 已兜底拦截: slider=$type")
                        param.result = false
                    }
                }
            }
        )
    }

    private fun tryHookPlugin(pluginCl: ClassLoader, source: String) {
        if (hookedClassLoaders.put(pluginCl, true) == true) {
            return
        }

        val brightnessCtrlClass = XposedHelpers.findClassIfExists(CLASS_BRIGHTNESS_CONTROLLER, pluginCl)
        val volumeCtrlClass = XposedHelpers.findClassIfExists(CLASS_VOLUME_CONTROLLER, pluginCl)
        val viewTouchAnimClass = XposedHelpers.findClassIfExists(CLASS_VIEW_TOUCH_ANIM, pluginCl)
        val gestureListenerClass = XposedHelpers.findClassIfExists(CLASS_GESTURE_LISTENER, pluginCl)

        if (brightnessCtrlClass == null || volumeCtrlClass == null ||
            viewTouchAnimClass == null || gestureListenerClass == null
        ) {
            if (source != "appClassLoader") {
                XposedBridge.log(
                    "$TAG: [Legacy] 插件类缺失($source): brightness=${brightnessCtrlClass != null}, " +
                        "volume=${volumeCtrlClass != null}, touchAnim=${viewTouchAnimClass != null}, " +
                        "gesture=${gestureListenerClass != null}"
                )
            }
            return
        }

        hookSliderController(brightnessCtrlClass, SliderType.BRIGHTNESS)
        hookSliderController(volumeCtrlClass, SliderType.VOLUME)
        hookGestureOnLongPress(gestureListenerClass)
        hookViewTouchAnimOnTouchEvent(viewTouchAnimClass)
        XposedBridge.log("$TAG: [Legacy] 已成功注入 miui.systemui.plugin 滑块 Hook ($source)")
    }

    private fun hookSliderController(controllerClass: Class<*>, type: SliderType) {
        val markSliderHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val slider = XposedHelpers.callMethod(param.thisObject, "getSlider") as? View ?: return
                if (sliderTypes.put(slider, type) != type) {
                    XposedBridge.log(
                        "$TAG: [Legacy] 已绑定滑块实例: type=$type, hash=${System.identityHashCode(slider)}"
                    )
                }
            }
        }
        XposedHelpers.findAndHookMethod(controllerClass, "onBindViewHolder", markSliderHook)
        XposedHelpers.findAndHookMethod(
            controllerClass,
            "setListening",
            Boolean::class.javaPrimitiveType,
            markSliderHook
        )
    }

    private fun hookGestureOnLongPress(gestureListenerClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            gestureListenerClass,
            "onLongPress",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val touchAnim = XposedHelpers.getObjectField(param.thisObject, "this$0") ?: return
                    val touchView = XposedHelpers.callMethod(touchAnim, "getTouchView") as? View ?: return
                    val type = sliderTypes[touchView] ?: return
                    val mode = getMode(type)
                    if (mode != PrefsConfig.MODE_SYSTEM_DEFAULT) {
                        XposedBridge.log("$TAG: [Legacy] 拦截原生 400ms 长按: slider=$type, mode=$mode")
                        param.result = null
                    }
                }
            }
        )
    }

    private fun hookViewTouchAnimOnTouchEvent(viewTouchAnimClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            viewTouchAnimClass,
            "onTouchEvent",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val touchAnim = param.thisObject ?: return
                    val event = param.args?.firstOrNull() as? MotionEvent ?: return
                    val touchView = XposedHelpers.callMethod(touchAnim, "getTouchView") as? View ?: return
                    val type = sliderTypes[touchView] ?: return

                    val state = customStates.getOrPut(touchAnim) { CustomLongPressState() }
                    val mode = getMode(type)
                    if (mode != PrefsConfig.MODE_CUSTOM_TIMEOUT) {
                        state.cancel()
                        return
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            state.cancel()
                            state.downRawX = event.rawX
                            state.downRawY = event.rawY
                            state.touchSlop = ViewConfiguration.get(touchView.context).scaledTouchSlop

                            val timeoutMs = getTimeoutMs(type)
                            XposedBridge.log("$TAG: [Legacy] 启动自定义长按计时: slider=$type, timeoutMs=$timeoutMs")
                            val runnable = Runnable {
                                state.pendingRunnable = null
                                if (touchView.isAttachedToWindow && touchView.isShown) {
                                    val handled = touchView.performLongClick()
                                    XposedBridge.log("$TAG: [Legacy] 自定义长按到期触发: slider=$type, handled=$handled")
                                    if (touchView.hasOnLongClickListeners()) {
                                        XposedHelpers.callMethod(touchAnim, "endScale", true)
                                    }
                                }
                            }
                            state.pendingRunnable = runnable
                            state.handler.postDelayed(runnable, timeoutMs)
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (state.pendingRunnable != null) {
                                val distance = hypot(
                                    (event.rawX - state.downRawX).toDouble(),
                                    (event.rawY - state.downRawY).toDouble()
                                )
                                if (distance > state.touchSlop) {
                                    state.cancel()
                                    XposedBridge.log("$TAG: [Legacy] 滑动距离超出阈值，取消自定义长按: slider=$type")
                                }
                            }
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            state.cancel()
                        }
                    }
                }
            }
        )
    }

    private fun reloadPrefs(): XSharedPreferences? {
        val sp = prefs ?: return null
        sp.reload()
        return sp
    }

    private fun getMode(type: SliderType): Int {
        val sp = reloadPrefs() ?: return PrefsConfig.DEFAULT_MODE
        val key = when (type) {
            SliderType.BRIGHTNESS -> PrefsConfig.KEY_BRIGHTNESS_MODE
            SliderType.VOLUME -> PrefsConfig.KEY_VOLUME_MODE
        }
        return sp.getInt(key, PrefsConfig.DEFAULT_MODE)
    }

    private fun getTimeoutMs(type: SliderType): Long {
        val sp = reloadPrefs() ?: return PrefsConfig.DEFAULT_TIMEOUT_MS.toLong()
        val key = when (type) {
            SliderType.BRIGHTNESS -> PrefsConfig.KEY_BRIGHTNESS_TIMEOUT_MS
            SliderType.VOLUME -> PrefsConfig.KEY_VOLUME_TIMEOUT_MS
        }
        return sp.getInt(key, PrefsConfig.DEFAULT_TIMEOUT_MS)
            .coerceIn(PrefsConfig.MIN_TIMEOUT_MS, PrefsConfig.MAX_TIMEOUT_MS)
            .toLong()
    }

    companion object {
        private const val TAG = "HyperSliderGuard"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val PLUGIN_PACKAGE_KEYWORD = "miui.systemui.plugin"
        private const val CLASS_BRIGHTNESS_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"
        private const val CLASS_VOLUME_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"
        private const val CLASS_VIEW_TOUCH_ANIM =
            "miui.systemui.controlcenter.widget.ViewTouchAnim"
        private const val CLASS_GESTURE_LISTENER =
            "miui.systemui.controlcenter.widget.ViewTouchAnim\$gestureListener\$1"
    }
}