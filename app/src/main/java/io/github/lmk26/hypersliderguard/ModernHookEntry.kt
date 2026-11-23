package io.github.lmk26.hypersliderguard

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dalvik.system.BaseDexClassLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

class ModernHookEntry : XposedModule() {

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
    private val baseHooksInstalled = AtomicBoolean(false)

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE) {
            return
        }

        if (remotePrefs == null) {
            remotePrefs = runCatching { getRemotePreferences(PrefsConfig.PREFS_NAME) }
                .onFailure { log(Log.ERROR, TAG, "获取 RemotePreferences 失败", it) }
                .getOrNull()
        }

        log(
            Log.INFO,
            TAG,
            "onPackageReady: pkg=${param.packageName}, api=$apiVersion, remotePrefsReady=${remotePrefs != null}"
        )

        installBaseHooksOnce()
        tryHookPlugin(param.classLoader, "appClassLoader")
    }

    private fun installBaseHooksOnce() {
        if (!baseHooksInstalled.compareAndSet(false, true)) {
            return
        }

        runCatching {
            val performLongClick = View::class.java.getDeclaredMethod(
                "performLongClick",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            hook(performLongClick).intercept { chain ->
                val view = chain.thisObject as? View
                val type = if (view != null) sliderTypes[view] else null
                if (type != null && getMode(type) == PrefsConfig.MODE_DISABLE) {
                    log(Log.INFO, TAG, "View.performLongClick 已兜底拦截: slider=$type")
                    false
                } else {
                    chain.proceed()
                }
            }
        }.onFailure {
            log(Log.WARN, TAG, "hook View.performLongClick 失败", it)
        }

        runCatching {
            for (ctor in BaseDexClassLoader::class.java.declaredConstructors) {
                hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    val dexPath = runCatching { chain.getArg(0) as? String }.getOrNull()
                    if (dexPath != null && dexPath.contains(PLUGIN_PACKAGE_KEYWORD)) {
                        val cl = chain.thisObject as? ClassLoader
                        if (cl != null) {
                            log(Log.INFO, TAG, "捕获插件 ClassLoader: dexPath=$dexPath")
                            tryHookPlugin(cl, "pluginDexClassLoader")
                        }
                    }
                    result
                }
            }
        }.onFailure {
            log(Log.WARN, TAG, "hook BaseDexClassLoader 构造函数失败", it)
        }
    }

    private fun tryHookPlugin(pluginCl: ClassLoader, source: String) {
        if (hookedClassLoaders.put(pluginCl, true) == true) {
            return
        }

        val brightnessCtrlClass = loadClassOrNull(pluginCl, CLASS_BRIGHTNESS_CONTROLLER)
        val volumeCtrlClass = loadClassOrNull(pluginCl, CLASS_VOLUME_CONTROLLER)
        val viewTouchAnimClass = loadClassOrNull(pluginCl, CLASS_VIEW_TOUCH_ANIM)
        val gestureListenerClass = loadClassOrNull(pluginCl, CLASS_GESTURE_LISTENER)

        if (brightnessCtrlClass == null || volumeCtrlClass == null ||
            viewTouchAnimClass == null || gestureListenerClass == null
        ) {
            if (source != "appClassLoader") {
                log(
                    Log.WARN,
                    TAG,
                    "插件类缺失($source): brightness=${brightnessCtrlClass != null}, " +
                        "volume=${volumeCtrlClass != null}, touchAnim=${viewTouchAnimClass != null}, " +
                        "gesture=${gestureListenerClass != null}"
                )
            }
            return
        }

        hookSliderController(brightnessCtrlClass, SliderType.BRIGHTNESS)
        hookSliderController(volumeCtrlClass, SliderType.VOLUME)
        hookGestureOnLongPress(gestureListenerClass, viewTouchAnimClass)
        hookViewTouchAnimOnTouchEvent(viewTouchAnimClass)
        log(Log.INFO, TAG, "已成功注入 miui.systemui.plugin 滑块 Hook ($source)")
    }

    private fun hookSliderController(controllerClass: Class<*>, type: SliderType) {
        val getSliderMethod = findMethodOrNull(controllerClass, "getSlider") ?: run {
            log(Log.WARN, TAG, "未找到目标方法: class=${controllerClass.name}, member=getSlider()")
            return
        }

        val markSlider: (Any?) -> Unit = { instance ->
            if (instance != null) {
                val slider = runCatching { getSliderMethod.invoke(instance) as? View }
                    .onFailure { log(Log.ERROR, TAG, "调用 getSlider 失败: class=${controllerClass.name}", it) }
                    .getOrNull()
                if (slider != null && sliderTypes.put(slider, type) != type) {
                    log(
                        Log.INFO,
                        TAG,
                        "已绑定滑块实例: type=$type, hash=${System.identityHashCode(slider)}"
                    )
                }
            }
        }

        val onBindMethod = findMethodOrNull(controllerClass, "onBindViewHolder")
        if (onBindMethod == null) {
            log(Log.WARN, TAG, "未找到目标方法: class=${controllerClass.name}, member=onBindViewHolder()")
        } else {
            runCatching {
                hook(onBindMethod).intercept { chain ->
                    val result = chain.proceed()
                    markSlider(chain.thisObject)
                    result
                }
            }.onFailure {
                log(Log.ERROR, TAG, "Hook 失败: ${controllerClass.name}.onBindViewHolder()", it)
            }
        }

        val setListeningMethod = findMethodOrNull(
            controllerClass,
            "setListening",
            Boolean::class.javaPrimitiveType!!
        )
        if (setListeningMethod == null) {
            log(Log.WARN, TAG, "未找到目标方法: class=${controllerClass.name}, member=setListening(boolean)")
        } else {
            runCatching {
                hook(setListeningMethod).intercept { chain ->
                    val result = chain.proceed()
                    markSlider(chain.thisObject)
                    result
                }
            }.onFailure {
                log(Log.ERROR, TAG, "Hook 失败: ${controllerClass.name}.setListening(boolean)", it)
            }
        }
    }

    private fun hookGestureOnLongPress(
        gestureListenerClass: Class<*>,
        viewTouchAnimClass: Class<*>
    ) {
        val onLongPress = findMethodOrNull(
            gestureListenerClass,
            "onLongPress",
            MotionEvent::class.java
        ) ?: run {
            log(Log.WARN, TAG, "未找到目标方法: class=${gestureListenerClass.name}, member=onLongPress(MotionEvent)")
            return
        }
        val outerField = findFieldOrNull(gestureListenerClass, "this\$0") ?: run {
            log(Log.WARN, TAG, "未找到目标字段: class=${gestureListenerClass.name}, member=this\$0")
            return
        }
        val getTouchView = findMethodOrNull(viewTouchAnimClass, "getTouchView") ?: run {
            log(Log.WARN, TAG, "未找到目标方法: class=${viewTouchAnimClass.name}, member=getTouchView()")
            return
        }

        runCatching {
            hook(onLongPress).intercept { chain ->
                val listener = chain.thisObject
                val touchAnim = if (listener != null) outerField.get(listener) else null
                val touchView = if (touchAnim != null) getTouchView.invoke(touchAnim) as? View else null
                val type = if (touchView != null) sliderTypes[touchView] else null
                if (type != null) {
                    val mode = getMode(type)
                    if (mode != PrefsConfig.MODE_SYSTEM_DEFAULT) {
                        log(Log.INFO, TAG, "拦截原生 400ms 长按: slider=$type, mode=$mode")
                        return@intercept null
                    }
                }
                chain.proceed()
            }
        }.onFailure {
            log(Log.ERROR, TAG, "Hook 失败: ${gestureListenerClass.name}.onLongPress(MotionEvent)", it)
        }
    }

    private fun hookViewTouchAnimOnTouchEvent(viewTouchAnimClass: Class<*>) {
        val onTouchEvent = findMethodOrNull(
            viewTouchAnimClass,
            "onTouchEvent",
            MotionEvent::class.java
        ) ?: run {
            log(Log.WARN, TAG, "未找到目标方法: class=${viewTouchAnimClass.name}, member=onTouchEvent(MotionEvent)")
            return
        }
        val getTouchView = findMethodOrNull(viewTouchAnimClass, "getTouchView") ?: run {
            log(Log.WARN, TAG, "未找到目标方法: class=${viewTouchAnimClass.name}, member=getTouchView()")
            return
        }
        val endScale = findMethodOrNull(
            viewTouchAnimClass,
            "endScale",
            Boolean::class.javaPrimitiveType!!
        )
        if (endScale == null) {
            log(Log.WARN, TAG, "未找到目标方法: class=${viewTouchAnimClass.name}, member=endScale(boolean)")
        }

        runCatching {
            hook(onTouchEvent).intercept { chain ->
                val result = chain.proceed()
                val touchAnim = chain.thisObject ?: return@intercept result
                val event = chain.getArg(0) as? MotionEvent ?: return@intercept result
                val touchView = getTouchView.invoke(touchAnim) as? View ?: return@intercept result
                val type = sliderTypes[touchView] ?: return@intercept result

                val state = customStates.getOrPut(touchAnim) { CustomLongPressState() }
                val mode = getMode(type)
                if (mode != PrefsConfig.MODE_CUSTOM_TIMEOUT) {
                    state.cancel()
                    return@intercept result
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        state.cancel()
                        state.downRawX = event.rawX
                        state.downRawY = event.rawY
                        state.touchSlop = ViewConfiguration.get(touchView.context).scaledTouchSlop

                        val timeoutMs = getTimeoutMs(type)
                        log(Log.INFO, TAG, "启动自定义长按计时: slider=$type, timeoutMs=$timeoutMs")
                        val runnable = Runnable {
                            state.pendingRunnable = null
                            if (touchView.isAttachedToWindow && touchView.isShown) {
                                val handled = touchView.performLongClick()
                                log(Log.INFO, TAG, "自定义长按到期触发: slider=$type, handled=$handled")
                                if (touchView.hasOnLongClickListeners()) {
                                    runCatching { endScale?.invoke(touchAnim, true) }
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
                                log(Log.INFO, TAG, "滑动距离超出阈值，取消自定义长按: slider=$type")
                            }
                        }
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        state.cancel()
                    }
                }
                result
            }
        }.onFailure {
            log(Log.ERROR, TAG, "Hook 失败: ${viewTouchAnimClass.name}.onTouchEvent(MotionEvent)", it)
        }
    }

    private fun getMode(type: SliderType): Int {
        val sp = remotePrefs ?: return PrefsConfig.DEFAULT_MODE
        val key = when (type) {
            SliderType.BRIGHTNESS -> PrefsConfig.KEY_BRIGHTNESS_MODE
            SliderType.VOLUME -> PrefsConfig.KEY_VOLUME_MODE
        }
        return sp.getInt(key, PrefsConfig.DEFAULT_MODE)
    }

    private fun getTimeoutMs(type: SliderType): Long {
        val sp = remotePrefs ?: return PrefsConfig.DEFAULT_TIMEOUT_MS.toLong()
        val key = when (type) {
            SliderType.BRIGHTNESS -> PrefsConfig.KEY_BRIGHTNESS_TIMEOUT_MS
            SliderType.VOLUME -> PrefsConfig.KEY_VOLUME_TIMEOUT_MS
        }
        return sp.getInt(key, PrefsConfig.DEFAULT_TIMEOUT_MS)
            .coerceIn(PrefsConfig.MIN_TIMEOUT_MS, PrefsConfig.MAX_TIMEOUT_MS)
            .toLong()
    }

    private fun loadClassOrNull(cl: ClassLoader, name: String): Class<*>? {
        return runCatching { Class.forName(name, false, cl) }.getOrNull()
    }

    private fun findMethodOrNull(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        return runCatching {
            clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        }.getOrNull()
    }

    private fun findFieldOrNull(clazz: Class<*>, name: String): Field? {
        return runCatching {
            clazz.getDeclaredField(name).apply { isAccessible = true }
        }.getOrNull()
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