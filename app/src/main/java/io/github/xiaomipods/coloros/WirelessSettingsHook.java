package io.github.xiaomipods.coloros;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class WirelessSettingsHook {
    private static final String TAG = "XiaomiPods";
    private static final String TARGET_CLASS = "h2.F";
    private static final String EXTRA_DEVICE = "device";

    private final XposedModule module;
    private final ClassLoader loader;
    private final Map<Object, SettingsPanel> panels = new WeakHashMap<>();

    WirelessSettingsHook(XposedModule module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    void install() {
        try {
            Class<?> target = Class.forName(TARGET_CLASS, false, loader);
            hook(target, "onCreate", Bundle.class, true);
            hook(target, "onResume", null, true);
            hook(target, "onPause", null, false);
            hook(target, "onDestroy", null, false);
            Log.i(TAG, "installed into " + target.getName());
        } catch (Throwable t) {
            Log.e(TAG, "wireless-settings hook failed", t);
        }
    }

    private void hook(Class<?> target, String name, Class<?> parameter, boolean after) {
        try {
            Method method = parameter == null
                    ? target.getDeclaredMethod(name)
                    : target.getDeclaredMethod(name, parameter);
            module.hook(method).intercept(chain -> {
                Object self = chain.getThisObject();
                if (after) {
                    Object result = chain.proceed();
                    safely(() -> afterLifecycle(self, name));
                    return result;
                }
                safely(() -> beforeLifecycle(self, name));
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "hook " + name + " skipped", t);
        }
    }

    private void afterLifecycle(Object fragment, String method) {
        if ("onCreate".equals(method)) {
            panelFor(fragment);
        } else if ("onResume".equals(method)) {
            SettingsPanel panel = panelFor(fragment);
            if (panel != null) panel.start();
        }
    }

    private void beforeLifecycle(Object fragment, String method) {
        SettingsPanel panel = panels.get(fragment);
        if (panel == null) return;
        if ("onPause".equals(method)) {
            panel.stop();
        } else if ("onDestroy".equals(method)) {
            panels.remove(fragment);
            panel.destroy();
        }
    }

    private SettingsPanel panelFor(Object fragment) {
        SettingsPanel existing = panels.get(fragment);
        if (existing != null) return existing;
        try {
            Context context = (Context) call(fragment, "getContext");
            if (context == null) return null;
            Bundle arguments = (Bundle) call(fragment, "getArguments");
            BluetoothDevice device = arguments == null
                    ? null
                    : arguments.getParcelable(EXTRA_DEVICE);
            if (device == null) {
                Context activity = (Context) call(fragment, "getActivity");
                if (activity != null) {
                    android.content.Intent intent = (android.content.Intent)
                            call(activity, "getIntent");
                    if (intent != null) {
                        device = intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                        if (device == null) device = intent.getParcelableExtra(EXTRA_DEVICE);
                    }
                }
            }
            if (!TargetDevice.isXiaomiHeadset(device)) return null;
            SettingsPanel panel = new SettingsPanel(fragment, context, device, loader);
            panels.put(fragment, panel);
            Log.i(TAG, "panel attached for " + device.getAddress() + " / " + device.getName());
            return panel;
        } catch (Throwable t) {
            Log.w(TAG, "panel target lookup failed", t);
            return null;
        }
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Log.w(TAG, "lifecycle action failed", t);
        }
    }
}
