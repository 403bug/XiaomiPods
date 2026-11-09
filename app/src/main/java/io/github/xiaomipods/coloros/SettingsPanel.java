package io.github.xiaomipods.coloros;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SettingsPanel {
    private static final String TAG = "XiaomiPods";
    private static final int CONFIG_GESTURE = 2;
    private static final int CONFIG_MULTIPOINT = 4;
    private static final int CONFIG_SOUND = 7;

    private static final int[] CLICK_SINGLE_ACTIONS = {8, 2, 3, 4, 5, 1, 9};
    private static final int[] CLICK_MULTI_ACTIONS = {8, 2, 3, 4, 5, 1};
    private static final int[] LONG_PRESS_ACTIONS = {0, 6, 9};
    private static final int[] SOUND_MODES = {0, 6, 1};

    private static final GestureSpec[] GESTURES = {
            new GestureSpec(4, "单击", CLICK_SINGLE_ACTIONS),
            new GestureSpec(1, "双击", CLICK_MULTI_ACTIONS),
            new GestureSpec(2, "三击", CLICK_MULTI_ACTIONS),
            new GestureSpec(3, "长按", LONG_PRESS_ACTIONS)
    };

    private final Object fragment;
    private final Context context;
    private final BluetoothDevice device;
    private final ClassLoader hostLoader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Object> preferences = new HashMap<>();

    private Object screen;
    private Object preferenceManager;
    private Object category;
    private Object statusPreference;
    private Object soundPreference;
    private Object multipointPreference;
    private XiaomiPodsClient client;
    private boolean started;
    private boolean destroyed;
    private boolean attached;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!started || destroyed) return;
            if (client != null) client.requestRefresh();
            main.postDelayed(this, 15_000);
        }
    };

    SettingsPanel(Object fragment, Context context, BluetoothDevice device, ClassLoader hostLoader) {
        this.fragment = fragment;
        this.context = context;
        this.device = device;
        this.hostLoader = hostLoader;
    }

    void start() {
        if (destroyed) return;
        started = true;
        ensureAttached();
        if (client != null) return;
        client = new XiaomiPodsClient(context, device, state -> {
            if (!destroyed) main.post(() -> render(state));
        });
        client.start();
        XiaomiPodsClient.DeviceState initial =
                new XiaomiPodsClient.DeviceState(device.getAddress(), displayName());
        initial.phase = "正在连接";
        render(initial);
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 15_000);
    }

    void stop() {
        started = false;
        main.removeCallbacks(refresh);
        if (client != null) {
            client.close();
            client = null;
        }
    }

    void destroy() {
        destroyed = true;
        stop();
        detach();
        preferences.clear();
    }

    private String displayName() {
        String name = device.getName();
        return name == null || name.isBlank() ? "小米耳机" : name;
    }

    private void ensureAttached() {
        if (attached) return;
        try {
            if (screen == null) {
                Object value = call(fragment, "getPreferenceScreen");
                if (value == null) return;
                screen = value;
            }
            if (preferenceManager == null) {
                preferenceManager = call(fragment, "getPreferenceManager");
            }
            if (preferenceManager == null) {
                for (String key : new String[] {
                        "function_container", "bt_abs_volume", "profile_container",
                        "le_profile_container", "bluetooth_device_mac_address"
                }) {
                    Object anchor = call(screen, "findPreference", key);
                    if (anchor == null) continue;
                    Object manager = call(anchor, "getPreferenceManager");
                    if (manager != null) {
                        preferenceManager = manager;
                        break;
                    }
                }
            }
            if (preferenceManager != null) {
                setPreferenceManager(screen, preferenceManager);
            }
            Log.i(TAG, "screen=" + screen.getClass().getName()
                    + " manager=" + (preferenceManager == null
                    ? "null" : preferenceManager.getClass().getName()));

            Object existing = call(screen, "findPreference", "headphone_function_key");
            if (existing != null) call(screen, "removePreference", existing);

            category = createPreference("PreferenceCategory");
            attachToManager(category, preferenceManager);
            call(category, "setTitle", "耳机功能");
            call(category, "setKey", "xiaomipods_controls");
            call(category, "setOrder", 401);
            call(screen, "addPreference", category);

            statusPreference = createPreference("Preference");
            attachToManager(statusPreference, preferenceManager);
            call(statusPreference, "setKey", "xiaomipods.status");
            call(statusPreference, "setSelectable", false);
            call(statusPreference, "setPersistent", false);
            call(statusPreference, "setTitle", displayName());
            call(statusPreference, "setSummary", "正在连接…");
            addPreference(statusPreference);

            for (GestureSpec gesture : GESTURES) {
                for (boolean left : new boolean[] {true, false}) {
                    addGesturePreference(gesture, left);
                }
            }
            attached = true;
        } catch (Throwable t) {
            Log.w(TAG, "unable to attach native panel", t);
        }
    }

    private void addGesturePreference(GestureSpec gesture, boolean left) throws Exception {
        Object preference = createPreference("ListPreference");
        attachToManager(preference, preferenceManager);
        String key = "xiaomipods.gesture." + gesture.clickType + "." + (left ? "L" : "R");
        call(preference, "setKey", key);
        call(preference, "setPersistent", false);
        call(preference, "setTitle", gesture.label + (left ? "左耳" : "右耳"));
        call(preference, "setDialogTitle", gesture.label + (left ? "左耳" : "右耳"));
        call(preference, "setEntries", new Object[] {labels(gesture.actions)});
        call(preference, "setEntryValues", new Object[] {values(gesture.actions)});
        call(preference, "setEnabled", false);
        call(preference, "setOnPreferenceChangeListener",
                changeListener((pref, value) -> {
                    int action = Integer.parseInt(String.valueOf(value));
                    call(pref, "setValue", String.valueOf(action));
                    call(pref, "setSummary", actionLabel(action));
                    if (client != null) client.setGesture(gesture.clickType, left, action);
                    return false;
                }));
        preferences.put(key, preference);
        addPreference(preference);
    }

    private void render(XiaomiPodsClient.DeviceState state) {
        if (destroyed) return;
        ensureAttached();
        if (statusPreference == null) return;

        String summary;
        if (state.connected) {
            summary = "已连接 · 左 " + percent(state.batteryLeft)
                    + " · 右 " + percent(state.batteryRight)
                    + " · 盒 " + percent(state.batteryCase)
                    + " · 固件 " + state.firmwareName();
        } else {
            summary = state.phase;
            if (state.error != null && !state.error.isBlank()) {
                summary += " · " + state.error;
            }
        }
        call(statusPreference, "setSummary", summary);
        call(statusPreference, "setTitle", displayName());

        boolean ready = state.connected && state.configs.containsKey(CONFIG_GESTURE);
        for (GestureSpec gesture : GESTURES) {
            for (boolean left : new boolean[] {true, false}) {
                String key = "xiaomipods.gesture." + gesture.clickType + "." + (left ? "L" : "R");
                Object preference = preferences.get(key);
                if (preference == null) continue;
                int action = state.gestureAction(gesture.clickType, left);
                call(preference, "setEnabled", ready);
                if (ready) {
                    call(preference, "setValue", String.valueOf(action));
                    call(preference, "setSummary", actionLabel(action));
                }
            }
        }

        Integer sound = state.mode(CONFIG_SOUND);
        if (sound != null) {
            ensureSoundPreference();
            call(soundPreference, "setEnabled", ready);
            call(soundPreference, "setValue", String.valueOf(sound));
            call(soundPreference, "setSummary", soundLabel(sound));
        }

        Integer multipoint = state.mode(CONFIG_MULTIPOINT);
        if (multipoint != null) {
            ensureMultipointPreference();
            call(multipointPreference, "setEnabled", ready);
            call(multipointPreference, "setChecked", multipoint == 1);
        }
    }

    private void ensureSoundPreference() {
        if (soundPreference != null) return;
        try {
            soundPreference = createPreference("ListPreference");
            attachToManager(soundPreference, preferenceManager);
            call(soundPreference, "setKey", "xiaomipods.sound");
            call(soundPreference, "setPersistent", false);
            call(soundPreference, "setTitle", "音效模式");
            call(soundPreference, "setDialogTitle", "音效模式");
            call(soundPreference, "setEntries", new Object[] {labels(SOUND_MODES)});
            call(soundPreference, "setEntryValues", new Object[] {values(SOUND_MODES)});
            call(soundPreference, "setOnPreferenceChangeListener",
                    changeListener((pref, value) -> {
                        int mode = Integer.parseInt(String.valueOf(value));
                        call(pref, "setValue", String.valueOf(mode));
                        call(pref, "setSummary", soundLabel(mode));
                        if (client != null) client.setSoundMode(mode);
                        return false;
                    }));
            addPreference(soundPreference);
        } catch (Throwable t) {
            Log.w(TAG, "sound preference failed", t);
        }
    }

    private void ensureMultipointPreference() {
        if (multipointPreference != null) return;
        try {
            multipointPreference = createPreference("SwitchPreference");
            attachToManager(multipointPreference, preferenceManager);
            call(multipointPreference, "setKey", "xiaomipods.multipoint");
            call(multipointPreference, "setPersistent", false);
            call(multipointPreference, "setTitle", "双设备连接");
            call(multipointPreference, "setSummary", "同时连接手机和另一台设备");
            call(multipointPreference, "setOnPreferenceChangeListener",
                    changeListener((pref, value) -> {
                        boolean enabled = Boolean.parseBoolean(String.valueOf(value));
                        call(pref, "setChecked", enabled);
                        if (client != null) client.setMultipoint(enabled);
                        return false;
                    }));
            addPreference(multipointPreference);
        } catch (Throwable t) {
            Log.w(TAG, "multipoint preference failed", t);
        }
    }

    private Object changeListener(ChangeHandler handler) {
        try {
            Class<?> preferenceClass = Class.forName(
                    "androidx.preference.Preference", false, hostLoader);
            Class<?> listenerClass = null;
            for (Method method : preferenceClass.getMethods()) {
                if (!"setOnPreferenceChangeListener".equals(method.getName())) continue;
                if (method.getParameterCount() != 1) continue;
                Class<?> candidate = method.getParameterTypes()[0];
                if (candidate.isInterface()) {
                    listenerClass = candidate;
                    break;
                }
            }
            if (listenerClass == null) {
                throw new ClassNotFoundException("Preference change listener");
            }
            return Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if ("onPreferenceChange".equals(method.getName())
                                && args != null && args.length >= 2) {
                            boolean result = handler.onChange(args[0], args[1]);
                            return result;
                        }
                        return false;
                    });
        } catch (Throwable t) {
            Log.w(TAG, "listener creation failed", t);
            return null;
        }
    }

    private void addPreference(Object preference) throws Exception {
        if (preference == category) {
            call(screen, "addPreference", category);
        } else if (category != null) {
            call(category, "addPreference", preference);
        } else {
            call(screen, "addPreference", preference);
        }
    }

    private Object createPreference(String simpleName) throws Exception {
        String couiName = "PreferenceCategory".equals(simpleName)
                ? "PreferenceCategory"
                : simpleName;
        Class<?> type = loadPreferenceClass("com.coui.appcompat.preference.COUI" + couiName);
        if (type == null) type = loadPreferenceClass("androidx.preference." + simpleName);
        if (type == null) throw new ClassNotFoundException(simpleName);
        try {
            Constructor<?> constructor = type.getConstructor(Context.class);
            return constructor.newInstance(context);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> constructor = type.getConstructor(
                    Context.class, android.util.AttributeSet.class);
            return constructor.newInstance(context, null);
        }
    }

    private Class<?> loadPreferenceClass(String className) {
        try {
            return Class.forName(className, true, hostLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void detach() {
        if (!attached || screen == null) return;
        try {
            if (category != null) call(screen, "removePreference", category);
        } catch (Throwable ignored) {
        }
        category = null;
        statusPreference = null;
        soundPreference = null;
        multipointPreference = null;
        attached = false;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "XiaomiPodsPreferenceListener";
            default -> null;
        };
    }

    private static String percent(int value) {
        return value < 0 ? "—" : value + "%";
    }

    private static CharSequence[] labels(int[] values) {
        CharSequence[] labels = new CharSequence[values.length];
        boolean sound = values == SOUND_MODES;
        for (int i = 0; i < values.length; i++) {
            labels[i] = sound ? soundLabel(values[i]) : actionLabel(values[i]);
        }
        return labels;
    }

    private static CharSequence[] values(int[] values) {
        CharSequence[] out = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) out[i] = String.valueOf(values[i]);
        return out;
    }

    private static String actionLabel(int action) {
        return switch (action) {
            case 0 -> "唤醒语音助手";
            case 1 -> "播放/暂停";
            case 2 -> "上一首";
            case 3 -> "下一首";
            case 4 -> "音量 +";
            case 5 -> "音量 −";
            case 6 -> "噪声控制";
            case 8 -> "无操作";
            case 9 -> "快捷拍照";
            case 12 -> "面对面翻译";
            case 13 -> "上/下一首";
            default -> action < 0 ? "未读取" : "未知操作 (" + action + ")";
        };
    }

    private static String soundLabel(int mode) {
        return switch (mode) {
            case 0 -> "默认";
            case 1 -> "人声增强";
            case 6 -> "高音增强";
            default -> "音效 " + mode;
        };
    }

    private static Object call(Object target, String methodName, Object... args) {
        if (target == null) return null;
        Method method = findMethod(target.getClass(), methodName, args);
        if (method == null) {
            throw new IllegalStateException("找不到方法 " + target.getClass().getName()
                    + "#" + methodName + "(" + argumentTypes(args) + ")");
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable t) {
            throw new IllegalStateException("调用失败 " + methodName, t);
        }
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (method.getParameterCount() != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!isAssignable(parameters[i], args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return method;
        }
        return null;
    }

    private static void attachToManager(Object preference, Object manager) {
        if (preference == null || manager == null) return;
        try {
            for (Class<?> type = preference.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!"onAttachedToHierarchy".equals(method.getName())) continue;
                    if (method.getParameterCount() != 1) continue;
                    if (!method.getParameterTypes()[0].isInstance(manager)) continue;
                    method.setAccessible(true);
                    method.invoke(preference, manager);
                    setPreferenceManager(preference, manager);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "attach preference to manager failed", t);
        }
    }

    private static void setPreferenceManager(Object preference, Object manager) {
        if (preference == null || manager == null) return;
        try {
            for (Class<?> type = preference.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("mPreferenceManager");
                    field.setAccessible(true);
                    field.set(preference, manager);
                    return;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "set preference manager field failed", t);
        }
    }

    private static boolean isAssignable(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (type.isPrimitive()) {
            if (type == boolean.class) return value instanceof Boolean;
            if (type == int.class) return value instanceof Integer;
            if (type == long.class) return value instanceof Long;
            return false;
        }
        return type.isAssignableFrom(value.getClass());
    }

    private static String argumentTypes(Object[] args) {
        return Arrays.stream(args).map(value -> value == null ? "null" : value.getClass().getSimpleName())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private interface ChangeHandler {
        boolean onChange(Object preference, Object newValue);
    }

    private static final class GestureSpec {
        final int clickType;
        final String label;
        final int[] actions;

        GestureSpec(int clickType, String label, int[] actions) {
            this.clickType = clickType;
            this.label = label;
            this.actions = actions;
        }
    }
}
