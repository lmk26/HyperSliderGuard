package io.github.lmk26.hypersliderguard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService

class MainActivity : Activity() {

    private data class SliderCardViews(
        val modeKey: String,
        val timeoutKey: String,
        val radioGroup: RadioGroup,
        val rbDisableId: Int,
        val rbCustomId: Int,
        val rbDefaultId: Int,
        val timeoutContainer: LinearLayout,
        val timeoutLabel: TextView,
        val seekBar: SeekBar
    )

    private lateinit var prefs: SharedPreferences
    private var usingPrivatePrefs = false
    private var serviceActivated = false
    private var isDark = false

    private var statusCardBg: GradientDrawable? = null
    private var statusTextView: TextView? = null
    private var statusHintView: TextView? = null
    private var privateWarningView: TextView? = null
    private val sliderCards = mutableListOf<SliderCardViews>()
    private var isRefreshingUi = false

    private val serviceObserver: (XposedService?) -> Unit = { service ->
        runOnUiThread {
            if (isDestroyed || isFinishing) {
                return@runOnUiThread
            }
            if (service != null) {
                serviceActivated = true
                usingPrivatePrefs = false
                prefs = service.getRemotePreferences(PrefsConfig.PREFS_NAME)
                refreshAllUi()
            } else {
                serviceActivated = false
                refreshStatusCard()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        XposedServiceManager.init()
        val existingService = XposedServiceManager.currentService
        if (existingService != null) {
            serviceActivated = true
            usingPrivatePrefs = false
            prefs = existingService.getRemotePreferences(PrefsConfig.PREFS_NAME)
        } else {
            prefs = obtainFallbackPrefs()
        }

        isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        LogManager.i(
            TAG,
            "MainActivity onCreate: serviceBound=${existingService != null}, legacyActive=${ModuleStatus.isActivated()}, isDark=$isDark"
        )

        setupSystemBarsAppearance()

        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F6F8")
        val cardColor = if (isDark) Color.parseColor("#1E1F22") else Color.WHITE
        val textPrimary = if (isDark) Color.parseColor("#F2F3F5") else Color.parseColor("#1A1C1E")
        val textSecondary = if (isDark) Color.parseColor("#A8ACB3") else Color.parseColor("#5C626B")

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(bgColor)
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        scrollView.setOnApplyWindowInsetsListener { view, insets ->
            val sysBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            insets
        }

        rootLayout.addView(createStatusCard(textPrimary, textSecondary))

        val warnText = TextView(this).apply {
            text = getString(R.string.prefs_private_warning)
            setTextColor(Color.parseColor("#E67E22"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(4), dp(4), dp(8))
            visibility = if (usingPrivatePrefs) View.VISIBLE else View.GONE
        }
        privateWarningView = warnText
        rootLayout.addView(warnText)

        rootLayout.addView(
            createSliderCard(
                title = getString(R.string.brightness_section_title),
                modeKey = PrefsConfig.KEY_BRIGHTNESS_MODE,
                timeoutKey = PrefsConfig.KEY_BRIGHTNESS_TIMEOUT_MS,
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
        )

        rootLayout.addView(
            createSliderCard(
                title = getString(R.string.volume_section_title),
                modeKey = PrefsConfig.KEY_VOLUME_MODE,
                timeoutKey = PrefsConfig.KEY_VOLUME_TIMEOUT_MS,
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
        )

        rootLayout.addView(
            createGeneralCard(
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
        )

        rootLayout.addView(
            createLogCard(
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
        )

        scrollView.addView(rootLayout)
        setContentView(scrollView)

        XposedServiceManager.addListener(serviceObserver)
    }

    override fun onDestroy() {
        XposedServiceManager.removeListener(serviceObserver)
        super.onDestroy()
    }

    private fun setupSystemBarsAppearance() {
        window.isNavigationBarContrastEnforced = false
        window.decorView.post {
            val controller = window.insetsController ?: return@post
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val appearance = if (isDark) 0 else mask
            controller.setSystemBarsAppearance(appearance, mask)
        }
    }

    private fun obtainFallbackPrefs(): SharedPreferences {
        usingPrivatePrefs = true
        return getSharedPreferences(PrefsConfig.PREFS_NAME, MODE_PRIVATE)
    }

    private fun isModuleActive(): Boolean {
        return serviceActivated || ModuleStatus.isActivated()
    }

    private fun isLauncherIconHidden(): Boolean {
        val launcherComponent = ComponentName(this, "$packageName.MainActivityLauncher")
        return packageManager.getComponentEnabledSetting(launcherComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun refreshAllUi() {
        refreshStatusCard()
        privateWarningView?.visibility = if (usingPrivatePrefs) View.VISIBLE else View.GONE

        isRefreshingUi = true
        try {
            for (card in sliderCards) {
                val mode = prefs.getInt(card.modeKey, PrefsConfig.DEFAULT_MODE)
                val timeout = prefs.getInt(card.timeoutKey, PrefsConfig.DEFAULT_TIMEOUT_MS)
                    .coerceIn(PrefsConfig.MIN_TIMEOUT_MS, PrefsConfig.MAX_TIMEOUT_MS)

                val checkedId = when (mode) {
                    PrefsConfig.MODE_CUSTOM_TIMEOUT -> card.rbCustomId
                    PrefsConfig.MODE_SYSTEM_DEFAULT -> card.rbDefaultId
                    else -> card.rbDisableId
                }
                card.radioGroup.check(checkedId)
                card.timeoutLabel.text = formatTimeoutLabel(timeout)
                val steps = (PrefsConfig.MAX_TIMEOUT_MS - PrefsConfig.MIN_TIMEOUT_MS) /
                    PrefsConfig.STEP_TIMEOUT_MS
                card.seekBar.progress = ((timeout - PrefsConfig.MIN_TIMEOUT_MS) /
                    PrefsConfig.STEP_TIMEOUT_MS).coerceIn(0, steps)
                card.timeoutContainer.visibility = if (mode == PrefsConfig.MODE_CUSTOM_TIMEOUT) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        } finally {
            isRefreshingUi = false
        }
    }

    private fun refreshStatusCard() {
        val activated = isModuleActive()
        val cardBgColor = when {
            activated && isDark -> Color.parseColor("#163322")
            activated -> Color.parseColor("#E8F7EE")
            isDark -> Color.parseColor("#362614")
            else -> Color.parseColor("#FFF4E5")
        }
        val statusColor = if (activated) {
            if (isDark) Color.parseColor("#6EE7A0") else Color.parseColor("#1B7F46")
        } else {
            if (isDark) Color.parseColor("#FFB86C") else Color.parseColor("#B85C00")
        }
        val textPrimary = if (isDark) Color.parseColor("#F2F3F5") else Color.parseColor("#1A1C1E")
        val textSecondary = if (isDark) Color.parseColor("#A8ACB3") else Color.parseColor("#5C626B")

        statusCardBg?.setColor(cardBgColor)
        statusTextView?.apply {
            text = getString(
                if (activated) R.string.status_activated else R.string.status_not_activated
            )
            setTextColor(statusColor)
        }
        statusHintView?.setTextColor(if (activated) textPrimary else textSecondary)
    }

    private fun createStatusCard(
        textPrimary: Int,
        textSecondary: Int
    ): View {
        val bgDrawable = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
        }
        statusCardBg = bgDrawable

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusTextView = statusView
        card.addView(statusView)

        val hintView = TextView(this).apply {
            text = getString(R.string.status_hint)
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        }
        statusHintView = hintView
        card.addView(hintView)

        refreshStatusCard()
        if (isModuleActive()) {
            hintView.setTextColor(textPrimary)
        }

        return card
    }

    private fun createSliderCard(
        title: String,
        modeKey: String,
        timeoutKey: String,
        cardColor: Int,
        textPrimary: Int,
        textSecondary: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
            }
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(titleView)

        val currentMode = prefs.getInt(modeKey, PrefsConfig.DEFAULT_MODE)
        val currentTimeout = prefs.getInt(timeoutKey, PrefsConfig.DEFAULT_TIMEOUT_MS)
            .coerceIn(PrefsConfig.MIN_TIMEOUT_MS, PrefsConfig.MAX_TIMEOUT_MS)

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val rbDisableId = View.generateViewId()
        val rbCustomId = View.generateViewId()
        val rbDefaultId = View.generateViewId()

        radioGroup.addView(createRadioButton(rbDisableId, getString(R.string.mode_disable), textPrimary))
        radioGroup.addView(createRadioButton(rbCustomId, getString(R.string.mode_custom_timeout), textPrimary))
        radioGroup.addView(createRadioButton(rbDefaultId, getString(R.string.mode_system_default), textPrimary))

        val checkedId = when (currentMode) {
            PrefsConfig.MODE_CUSTOM_TIMEOUT -> rbCustomId
            PrefsConfig.MODE_SYSTEM_DEFAULT -> rbDefaultId
            else -> rbDisableId
        }
        radioGroup.check(checkedId)
        card.addView(radioGroup)

        val timeoutContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            visibility = if (currentMode == PrefsConfig.MODE_CUSTOM_TIMEOUT) View.VISIBLE else View.GONE
        }

        val timeoutLabel = TextView(this).apply {
            text = formatTimeoutLabel(currentTimeout)
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(6))
        }
        timeoutContainer.addView(timeoutLabel)

        val steps = (PrefsConfig.MAX_TIMEOUT_MS - PrefsConfig.MIN_TIMEOUT_MS) / PrefsConfig.STEP_TIMEOUT_MS
        val seekBar = SeekBar(this).apply {
            max = steps
            progress = ((currentTimeout - PrefsConfig.MIN_TIMEOUT_MS) / PrefsConfig.STEP_TIMEOUT_MS)
                .coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = PrefsConfig.MIN_TIMEOUT_MS + progress * PrefsConfig.STEP_TIMEOUT_MS
                    timeoutLabel.text = formatTimeoutLabel(ms)
                    if (fromUser && !isRefreshingUi) {
                        prefs.edit().putInt(timeoutKey, ms).apply()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val ms = PrefsConfig.MIN_TIMEOUT_MS + (seekBar?.progress ?: 0) * PrefsConfig.STEP_TIMEOUT_MS
                    LogManager.i(TAG, "修改时长配置: $timeoutKey=$ms ms")
                }
            })
        }
        timeoutContainer.addView(seekBar)
        card.addView(timeoutContainer)

        radioGroup.setOnCheckedChangeListener { _, id ->
            if (isRefreshingUi) {
                return@setOnCheckedChangeListener
            }
            val mode = when (id) {
                rbCustomId -> PrefsConfig.MODE_CUSTOM_TIMEOUT
                rbDefaultId -> PrefsConfig.MODE_SYSTEM_DEFAULT
                else -> PrefsConfig.MODE_DISABLE
            }
            prefs.edit().putInt(modeKey, mode).apply()
            LogManager.i(TAG, "修改模式配置: $modeKey=$mode")
            timeoutContainer.visibility = if (mode == PrefsConfig.MODE_CUSTOM_TIMEOUT) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        sliderCards.add(
            SliderCardViews(
                modeKey = modeKey,
                timeoutKey = timeoutKey,
                radioGroup = radioGroup,
                rbDisableId = rbDisableId,
                rbCustomId = rbCustomId,
                rbDefaultId = rbDefaultId,
                timeoutContainer = timeoutContainer,
                timeoutLabel = timeoutLabel,
                seekBar = seekBar
            )
        )

        return card
    }

    private fun createGeneralCard(
        cardColor: Int,
        textPrimary: Int,
        textSecondary: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
            }
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.general_section_title)
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(titleView)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dp(12)
            }
        }

        val switchTitle = TextView(this).apply {
            text = getString(R.string.hide_launcher_icon_title)
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        textContainer.addView(switchTitle)

        val switchDesc = TextView(this).apply {
            text = getString(R.string.hide_launcher_icon_desc)
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(2), 0, 0)
        }
        textContainer.addView(switchDesc)

        val launcherComponent = ComponentName(this, "$packageName.MainActivityLauncher")
        val hideSwitch = Switch(this).apply {
            isChecked = isLauncherIconHidden()
            setOnCheckedChangeListener { _, checked ->
                val newState = if (checked) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                packageManager.setComponentEnabledSetting(
                    launcherComponent,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
                LogManager.i(TAG, "切换隐藏桌面图标: hidden=$checked")
            }
        }

        row.addView(textContainer)
        row.addView(hideSwitch)
        card.addView(row)

        return card
    }

    private fun createLogCard(
        cardColor: Int,
        textPrimary: Int,
        textSecondary: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
            }
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.log_section_title)
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        card.addView(titleView)

        val descView = TextView(this).apply {
            text = getString(R.string.log_section_desc)
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(descView)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val primaryColor = Color.parseColor("#2563EB")
        val secondaryBg = if (isDark) Color.parseColor("#2B2D31") else Color.parseColor("#E9ECEF")

        val copyBtn = Button(this).apply {
            text = getString(R.string.btn_copy_logs)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                val report = LogManager.buildDiagnosticReport(
                    context = this@MainActivity,
                    service = XposedServiceManager.currentService,
                    prefs = prefs,
                    isIconHidden = isLauncherIconHidden()
                )
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("HyperSliderGuard 诊断日志", report))
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_logs_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val clearBtn = Button(this).apply {
            text = getString(R.string.btn_clear_logs)
            setTextColor(textPrimary)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(secondaryBg)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                LogManager.clear()
                LogManager.i(TAG, "用户手动清空应用日志")
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_logs_cleared),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        buttonRow.addView(copyBtn)
        buttonRow.addView(clearBtn)
        card.addView(buttonRow)

        return card
    }

    private fun createRadioButton(viewId: Int, label: String, textColor: Int): RadioButton {
        return RadioButton(this).apply {
            id = viewId
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
    }

    private fun formatTimeoutLabel(ms: Int): String {
        return getString(R.string.timeout_label_format, ms, ms / 1000f)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG = "HyperSliderGuard"
    }
}