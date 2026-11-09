package io.github.xiaomipods.coloros;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;

import java.util.Locale;

final class TargetDevice {
    private static final String[] XIAOMI_MARKERS = {"xiaomi", "redmi", "小米", "红米"};
    private static final String[] NON_HEADSET_MARKERS = {
            "band", "watch", "pad", "tablet", "tv", "box", "router", "keyboard",
            "mouse", "speaker", "sound", "音箱", "手环", "手表", "平板", "电视",
            "键盘", "鼠标"
    };

    private TargetDevice() {}

    static boolean isXiaomiHeadset(BluetoothDevice device) {
        if (device == null) return false;
        String name = device.getName();
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        boolean xiaomi = false;
        for (String marker : XIAOMI_MARKERS) {
            if (lower.contains(marker)) {
                xiaomi = true;
                break;
            }
        }
        if (!xiaomi) return false;
        for (String marker : NON_HEADSET_MARKERS) {
            if (lower.contains(marker)) return false;
        }
        BluetoothClass btClass = device.getBluetoothClass();
        if (btClass == null) return true;
        int major = btClass.getMajorDeviceClass();
        int deviceClass = btClass.getDeviceClass();
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
                || deviceClass == 0x240404;
    }
}
