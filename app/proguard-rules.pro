# 保留 Xposed Modern API 101/102 与 Legacy API 82 入口类及成员
-keep class io.github.lmk26.hypersliderguard.ModernHookEntry {
    public <init>();
    <methods>;
}
-keep class io.github.lmk26.hypersliderguard.HookEntry {
    public <init>();
    <methods>;
}
-keep class io.github.lmk26.hypersliderguard.ModuleStatus {
    <methods>;
}

# 保留 LibXposed 与旧版 Xposed 接口及服务提供者
-keep class io.github.libxposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn io.github.libxposed.**
-dontwarn de.robv.android.xposed.**