# HyperSliderGuard

**简体中文** | [English](README.md)

专为 **小米 HyperOS 控制中心** 打造的 Xposed/LSPosed 模块，用于禁用亮度条与音量条的长按展开更多选项功能，或分别自定义长按触发时间。

## 功能特性

- **亮度/音量独立配置**：可分别为 **亮度条** 与 **音量条** 选择以下模式：
  - **禁用长按（默认）**：拖动调节亮度或音量时彻底避免误触展开二级面板。
  - **自定义长按触发时间**：支持 **400 ms ~ 3000 ms** 调节（步进 50 ms）；上下滑动调节进度超过系统阈值时自动取消长按计时。
  - **系统默认**：恢复系统原生触发行为（约 400 ms）。
- **静态作用域**：基于 Modern Xposed API（`META-INF/xposed/scope.list` + `staticScope=true`），启用模块后自动锁定 `com.android.systemui`。
- **配置热生效**：通过 `RemotePreferences` 跨进程同步配置，修改设置后无需重启系统界面即可生效。
- **隐藏桌面图标**：支持隐藏桌面启动器图标，隐藏后仍可从 LSPosed 模块列表点击进入设置页。
- **一键复制诊断日志**：内置设备信息、模块状态、配置快照与本进程 Logcat 收集，方便反馈排查。

## 已测试环境与版本

| 项目 | 已验证版本 |
| --- | --- |
| **模块版本** | `1.0.0` (`versionCode: 100`) |
| **测试机型** | Xiaomi 14 (`houji` / `23127PN0CC`) |
| **Android 版本** | Android 16 (API 36) |
| **系统版本** | HyperOS 3.0 (`OS3.0.307.0.WNCCNXM`) |
| **框架环境** | LSPosed `v2.2.1 (7912)` |
| **模块 API** | LibXposed API `102.0.0`（保留 Legacy Xposed API `82` 兼容兜底） |
| **宿主插件** | `miui.systemui.plugin`（由 `com.android.systemui` 动态加载） |

## 使用方法

1. 安装 **HyperSliderGuard** APK。
2. 打开 **LSPosed 管理器** -> **模块** -> 启用 **HyperSliderGuard**（静态作用域已自动锁定 **系统界面 `com.android.systemui`**）。
3. 首次启用后在 LSPosed 中重启一次 **系统界面**。
4. 从桌面或 LSPosed 模块设置进入 **HyperSliderGuard**，修改配置后立即生效。

## 实现原理

1. 在 `com.android.systemui` 中 Hook `BaseDexClassLoader` 构造函数，捕获动态加载的 `miui.systemui.plugin` 插件 `ClassLoader`。
2. Hook `BrightnessSliderController` 与 `VolumeSliderController` 的 `onBindViewHolder` / `setListening`，通过 `getSlider()` 分别标记亮度与音量 `VerticalSeekBar` 实例。
3. 在 `ViewTouchAnim$gestureListener$1.onLongPress` 拦截原生 400 ms 长按（禁用模式下同时在 `View.performLongClick` 兜底）。
4. 自定义时长模式下，在 `ViewTouchAnim.onTouchEvent` 使用主线程 `Handler.postDelayed` 计时，并基于 `rawX` / `rawY` 位移判断是否取消，避免滑动调节时误触发。

## 构建说明

- **JDK**：Java 17+
- **Android Gradle Plugin**：`9.5.2`（Built-in Kotlin）
- **Gradle**：`9.9.1`
- **compileSdk**：`37`，**minSdk**：`31`，**targetSdk**：`37`

```bash
gradle assembleDebug
```

## 问题反馈与日志收集

如果在新版系统或其它机型上出现未生效，或需要增加适配版本，请一并附上以下内容：
1. **模块诊断日志**：在 **HyperSliderGuard** 设置页点击 **「复制日志」** 获取设备信息与配置诊断报告。
2. **宿主侧 Hook 日志**：在 **LSPosed 管理器 -> 日志** 页面筛选标签 `HyperSliderGuard`，导出系统界面（`com.android.systemui`）宿主侧日志。
3. **当前版本的宿主 APK**：提供你当前系统版本对应的宿主 APK（`miui.systemui.plugin` 及/或 `com.android.systemui` / `MiuiSystemUI.apk`）。