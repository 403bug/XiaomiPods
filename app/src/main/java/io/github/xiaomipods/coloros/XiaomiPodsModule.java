package io.github.xiaomipods.coloros;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class XiaomiPodsModule extends XposedModule {
    private static final String TAG = "XiaomiPods";
    private static final String PKG_WIRELESS_SETTINGS = "com.oplus.wirelesssettings";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!PKG_WIRELESS_SETTINGS.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            new WirelessSettingsHook(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            INSTALLED.set(false);
            Log.e(TAG, "host install failed", t);
        }
    }
}
