# HyperSliderGuard

[简体中文](README.md) | **English**

An Xposed/LSPosed module for **Xiaomi HyperOS Control Center** that disables the long-press expansion gesture on Brightness and Volume sliders, or lets you customize the long-press trigger delay independently.

## Features

- **Independent Control**: Configure **Brightness Slider** and **Volume Slider** separately:
  - **Disable Long-Press (Default)**: Prevents accidental opening of secondary detail panels when adjusting sliders.
  - **Custom Long-Press Delay**: Adjust trigger delay from **400 ms to 3000 ms** (50 ms step). Cancels automatically if your finger moves beyond touch slop while dragging.
  - **System Default**: Keeps native HyperOS behavior (~400 ms).
- **Static Scope**: Uses Modern Xposed API (`META-INF/xposed/scope.list` with `staticScope=true`) to target `com.android.systemui` automatically.
- **Hot Reload Preferences**: Configuration changes take effect immediately via `RemotePreferences` without restarting System UI.
- **Hide Launcher Icon**: Option to hide the app icon from the home screen while keeping access via LSPosed Manager module settings.
- **One-Tap Diagnostic Report**: Built-in log collector to copy device info, framework status, preferences snapshot, and process logcat for issue reporting.

## Tested Environment

| Item | Verified Version |
| --- | --- |
| **Module Version** | `1.0.0` (`versionCode: 100`) |
| **Device** | Xiaomi 14 (`houji` / `23127PN0CC`) |
| **Android Version** | Android 16 (API 36) |
| **OS Build** | HyperOS 3.0 (`OS3.0.307.0.WNCCNXM`) |
| **Xposed Framework** | LSPosed `v2.2.1 (7912)` |
| **Xposed API** | LibXposed API `102.0.0` (with Legacy Xposed API `82` fallback) |
| **Target Host Plugin** | `miui.systemui.plugin` (dynamically loaded by `com.android.systemui`) |

## Usage

1. Install **HyperSliderGuard** APK.
2. Open **LSPosed Manager** -> **Modules** -> enable **HyperSliderGuard** (scope `com.android.systemui` is locked automatically by static scope).
3. Restart **System UI** (`com.android.systemui`) once.
4. Open **HyperSliderGuard** from your launcher or LSPosed Manager and adjust settings anytime with immediate effect.

## How It Works

1. Hooks `BaseDexClassLoader` constructors inside `com.android.systemui` to capture the dynamically loaded `miui.systemui.plugin` `ClassLoader`.
2. Hooks `BrightnessSliderController` and `VolumeSliderController` (`onBindViewHolder`, `setListening`) to tag each `VerticalSeekBar` instance as Brightness or Volume.
3. Intercepts `ViewTouchAnim$gestureListener$1.onLongPress` (and `View.performLongClick` as a fallback in disable mode) to block the native 400 ms trigger.
4. In custom delay mode, schedules a main-thread `Handler` timer in `ViewTouchAnim.onTouchEvent` using `rawX` / `rawY` distance checks to avoid false triggers during slider dragging.

## Build from Source

- **JDK**: Java 17+
- **Android Gradle Plugin**: `9.5.2` (Built-in Kotlin)
- **Gradle**: `9.9.1`
- **compileSdk**: `37`, **minSdk**: `31`, **targetSdk**: `37`

```bash
gradle assembleDebug
```

## Feedback & Logs

If the module does not work on your HyperOS / MIUI version or you want to request support for another version, please attach the following:
1. **Diagnostic Logs**: Open **HyperSliderGuard** -> **Diagnostics & Logs** -> tap **Copy Logs**.
2. **Host Hook Logs**: Open **LSPosed Manager** -> **Logs** -> filter by tag `HyperSliderGuard` to collect host-side (`com.android.systemui`) logs.
3. **Host APK**: Provide the host APK from your current system version (`miui.systemui.plugin` and/or `com.android.systemui` / `MiuiSystemUI.apk`).