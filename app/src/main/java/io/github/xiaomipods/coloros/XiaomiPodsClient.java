package io.github.xiaomipods.coloros;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class XiaomiPodsClient implements Closeable {
    private static final String TAG = "XiaomiPods";
    private static final UUID SPP_UUID =
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB");
    private static final int FLAGS_COMMAND = 0xC4;
    private static final int FLAGS_RESPONSE = 0x04;
    private static final int REQUEST_TIMEOUT_MS = 3000;
    private static final int CONFIG_GESTURE = 2;
    private static final int CONFIG_MULTIPOINT = 4;
    private static final int CONFIG_SOUND = 7;
    private static final int CONFIG_ADAPTIVE_VOLUME = 72;

    interface Listener {
        void onState(DeviceState state);
    }

    static final class DeviceState {
        final String address;
        final String name;
        String phase = "正在连接";
        String error = "";
        boolean connected;
        int batteryLeft = -1;
        int batteryRight = -1;
        int batteryCase = -1;
        int firmware;
        int vendorId;
        int productId;
        final Map<Integer, byte[]> configs = new HashMap<>();

        DeviceState(String address, String name) {
            this.address = address;
            this.name = name;
        }

        DeviceState copy() {
            DeviceState copy = new DeviceState(address, name);
            copy.phase = phase;
            copy.error = error;
            copy.connected = connected;
            copy.batteryLeft = batteryLeft;
            copy.batteryRight = batteryRight;
            copy.batteryCase = batteryCase;
            copy.firmware = firmware;
            copy.vendorId = vendorId;
            copy.productId = productId;
            for (Map.Entry<Integer, byte[]> e : configs.entrySet()) {
                copy.configs.put(e.getKey(), e.getValue().clone());
            }
            return copy;
        }

        String firmwareName() {
            if (firmware < 0) return "—";
            return ((firmware >> 12) & 0xF) + "." + ((firmware >> 8) & 0xF) + "."
                    + ((firmware >> 4) & 0xF) + "." + (firmware & 0xF);
        }

        int gestureAction(int clickType, boolean left) {
            byte[] value = configs.get(CONFIG_GESTURE);
            if (value == null) return -1;
            for (int i = 0; i + 2 < value.length; i += 3) {
                if ((value[i] & 0xFF) == clickType) {
                    return value[i + (left ? 1 : 2)] & 0xFF;
                }
            }
            return -1;
        }

        Integer mode(int configId) {
            byte[] value = configs.get(configId);
            return value != null && value.length > 0 ? value[0] & 0xFF : null;
        }
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final String address;
    private final Listener listener;
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final BlockingQueue<RcspPacket> responses = new LinkedBlockingQueue<>();
    private final SecureRandom random = new SecureRandom();
    private final CountDownLatch devicePairDone = new CountDownLatch(1);
    private final Object writeLock = new Object();

    private volatile boolean running = true;
    private volatile int sequence = 0;
    private volatile int waitingOpcode = -1;
    private volatile int waitingSequence = -1;
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;
    private Thread worker;
    private Thread reader;
    private DeviceState state;

    XiaomiPodsClient(Context context, BluetoothDevice device, Listener listener) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        this.adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        this.address = device.getAddress();
        this.listener = listener;
        this.state = new DeviceState(address, device.getName());
    }

    String address() {
        return address;
    }

    DeviceState state() {
        return state.copy();
    }

    void start() {
        worker = new Thread(this::workerLoop, "xiaomipods-control");
        worker.start();
    }

    void requestRefresh() {
        if (!running) return;
        commands.offer(this::queryBattery);
    }

    void setGesture(int clickType, boolean left, int action) {
        if (!running) return;
        commands.offer(() -> {
            try {
                updateGesture(clickType, left, action);
            } catch (Throwable t) {
                state.error = friendly(t);
                publish();
            }
        });
    }

    void setSoundMode(int mode) {
        if (!running) return;
        commands.offer(() -> {
            try {
                updateMode(CONFIG_SOUND, mode, "音效模式");
            } catch (Throwable t) {
                state.error = friendly(t);
                publish();
            }
        });
    }

    void setMultipoint(boolean enabled) {
        if (!running) return;
        commands.offer(() -> {
            try {
                updateMode(CONFIG_MULTIPOINT, enabled ? 1 : 0, "双设备连接");
            } catch (Throwable t) {
                state.error = friendly(t);
                publish();
            }
        });
    }

    private void publish() {
        listener.onState(state.copy());
    }

    private void workerLoop() {
        try {
            connect();
            authenticate();
            state.phase = "读取耳机设置";
            publish();
            queryTargetInfo();
            queryConfigs();
            queryBattery();
            state.phase = "已连接";
            state.connected = true;
            publish();

            while (running) {
                Runnable command = commands.poll(1, TimeUnit.SECONDS);
                if (command != null) {
                    try {
                        command.run();
                    } catch (Throwable t) {
                        Log.w(TAG, "command failed", t);
                        state.error = friendly(t);
                        publish();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "session failed", t);
            state.connected = false;
            state.error = friendly(t);
            state.phase = "连接失败";
            publish();
        } finally {
            closeSocket();
        }
    }

    @SuppressLint("MissingPermission")
    private void connect() throws IOException {
        BluetoothDevice device = adapter.getRemoteDevice(address);
        adapter.cancelDiscovery();
        BluetoothSocket candidate = null;
        IOException first = null;
        for (int i = 0; i < 2; i++) {
            try {
                candidate = i == 0
                        ? device.createRfcommSocketToServiceRecord(SPP_UUID)
                        : device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                candidate.connect();
                break;
            } catch (IOException e) {
                if (first == null) first = e;
                closeQuietly(candidate);
                candidate = null;
            }
        }
        if (candidate == null) {
            throw first != null ? first : new IOException("无法建立耳机控制通道");
        }
        socket = candidate;
        input = socket.getInputStream();
        output = socket.getOutputStream();
        reader = new Thread(this::readLoop, "xiaomipods-reader");
        reader.start();
        state.phase = "认证中";
        publish();
    }

    private void authenticate() throws Exception {
        byte[] challenge = new byte[16];
        random.nextBytes(challenge);
        int authSn = nextSequence();
        byte[] authData = concat(new byte[] {(byte) 0x01}, challenge);
        RcspPacket response = request(0x50, authSn, authData);
        byte[] result = response.responseData();
        if (result.length < 17 || result[0] != 0x01) {
            throw new IOException("耳机认证应答格式错误");
        }
        byte[] expected = XiaomiAuth.transform(challenge);
        byte[] actual = Arrays.copyOfRange(result, 1, 17);
        if (!Arrays.equals(expected, actual)) {
            throw new IOException("耳机认证失败");
        }

        int calcSn = nextSequence();
        response = request(0x51, calcSn, new byte[] {0x01, 0x00});
        if (response.responseData().length < 1) {
            throw new IOException("耳机认证确认失败");
        }

        try {
            devicePairDone.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void queryTargetInfo() throws Exception {
        RcspPacket response = request(0x02, nextSequence(),
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        byte[] data = response.responseData();
        int pos = 0;
        while (pos + 2 <= data.length) {
            int length = data[pos] & 0xFF;
            if (length < 1 || pos + 1 + length > data.length) break;
            int type = data[pos + 1] & 0xFF;
            byte[] value = Arrays.copyOfRange(data, pos + 2, pos + 1 + length);
            pos += 1 + length;
            if (type == 1 && value.length >= 4) {
                state.firmware = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
            } else if (type == 3 && value.length >= 4) {
                state.vendorId = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
                state.productId = ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
            } else if (type == 7 && value.length >= 3) {
                applyBattery(value[0], value[1], value[2]);
            }
        }
        if (state.vendorId != 0 && state.vendorId != 0x2717) {
            throw new IOException("不是小米耳机（VID 0x" + Integer.toHexString(state.vendorId) + "）");
        }
        publish();
    }

    private void queryConfigs() throws Exception {
        int sn = nextSequence();
        byte[] payload = {
                (byte) (CONFIG_GESTURE >> 8), (byte) CONFIG_GESTURE,
                (byte) (CONFIG_MULTIPOINT >> 8), (byte) CONFIG_MULTIPOINT,
                (byte) (CONFIG_SOUND >> 8), (byte) CONFIG_SOUND,
                (byte) (CONFIG_ADAPTIVE_VOLUME >> 8), (byte) CONFIG_ADAPTIVE_VOLUME
        };
        RcspPacket response = request(0xF3, sn, payload);
        state.configs.putAll(parseConfigs(response.responseData()));
        publish();
    }

    private void queryBattery() {
        try {
            if (socket == null || !socket.isConnected()) return;
            RcspPacket response = request(0x02, nextSequence(),
                    new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            byte[] data = response.responseData();
            int pos = 0;
            while (pos + 2 <= data.length) {
                int length = data[pos] & 0xFF;
                if (length < 1 || pos + 1 + length > data.length) break;
                int type = data[pos + 1] & 0xFF;
                if (type == 7 && length >= 4) {
                    applyBattery(data[pos + 2], data[pos + 3], data[pos + 4]);
                }
                pos += 1 + length;
            }
            publish();
        } catch (Throwable t) {
            Log.w(TAG, "battery refresh failed", t);
        }
    }

    private void updateGesture(int clickType, boolean left, int action) throws Exception {
        byte[] value = {(byte) clickType, left ? (byte) action : (byte) 0xFF,
                left ? (byte) 0xFF : (byte) action};
        request(0xF2, nextSequence(), config(CONFIG_GESTURE, value));
        byte[] current = state.configs.get(CONFIG_GESTURE);
        if (current == null) current = new byte[0];
        byte[] merged = mergeGesture(current, clickType, left, action);
        state.configs.put(CONFIG_GESTURE, merged);
        state.error = "";
        publish();
    }

    private void updateMode(int configId, int mode, String label) throws Exception {
        request(0xF2, nextSequence(), config(configId, new byte[] {(byte) mode}));
        state.configs.put(configId, new byte[] {(byte) mode});
        state.error = "";
        publish();
    }

    private static byte[] mergeGesture(byte[] current, int clickType, boolean left, int action) {
        byte[] out = current.clone();
        boolean found = false;
        for (int i = 0; i + 2 < out.length; i += 3) {
            if ((out[i] & 0xFF) == clickType) {
                out[i + (left ? 1 : 2)] = (byte) action;
                found = true;
                break;
            }
        }
        if (!found) {
            out = Arrays.copyOf(out, out.length + 3);
            out[out.length - 3] = (byte) clickType;
            out[out.length - 2] = left ? (byte) action : (byte) 0xFF;
            out[out.length - 1] = left ? (byte) 0xFF : (byte) action;
        }
        return out;
    }

    private static byte[] config(int configId, byte[] value) {
        byte[] out = new byte[3 + value.length];
        out[0] = (byte) (value.length + 2);
        out[1] = (byte) (configId >> 8);
        out[2] = (byte) configId;
        System.arraycopy(value, 0, out, 3, value.length);
        return out;
    }

    private static Map<Integer, byte[]> parseConfigs(byte[] data) {
        Map<Integer, byte[]> configs = new LinkedHashMap<>();
        int pos = 0;
        while (pos + 3 <= data.length) {
            int length = data[pos] & 0xFF;
            if (length == 0xFF && pos + 5 <= data.length) {
                int type = ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
                int valueLength = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                if (pos + 5 + valueLength > data.length) break;
                configs.put(type, Arrays.copyOfRange(data, pos + 5, pos + 5 + valueLength));
                pos += 5 + valueLength;
                continue;
            }
            if (length < 2 || pos + 1 + length > data.length) break;
            int type = ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
            configs.put(type, Arrays.copyOfRange(data, pos + 3, pos + 1 + length));
            pos += 1 + length;
        }
        return configs;
    }

    private void applyBattery(byte left, byte right, byte caseValue) {
        state.batteryLeft = batteryValue(left);
        state.batteryRight = batteryValue(right);
        state.batteryCase = batteryValue(caseValue);
    }

    private static int batteryValue(byte value) {
        int raw = value & 0xFF;
        if (raw == 0xFF) return -1;
        int percent = raw & 0x7F;
        return percent <= 100 ? percent : -1;
    }

    private synchronized int nextSequence() {
        int value = sequence++;
        if (sequence > 255) sequence = 0;
        return value & 0xFF;
    }

    private RcspPacket request(int opcode, int sn, byte[] data) throws Exception {
        responses.clear();
        waitingOpcode = opcode;
        waitingSequence = sn;
        byte[] body = concat(new byte[] {(byte) sn}, data);
        writeFrame(RcspPacket.frame(FLAGS_COMMAND, opcode, body));
        RcspPacket response;
        do {
            response = responses.poll(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } while (response != null && (response.opcode != opcode || response.sequence() != sn));
        waitingOpcode = -1;
        waitingSequence = -1;
        if (response == null) {
            throw new IOException("耳机响应超时 (0x" + Integer.toHexString(opcode) + ")");
        }
        if (response.status() != 0) {
            throw new IOException("耳机返回错误 " + response.status());
        }
        return response;
    }

    private void writeFrame(byte[] frame) throws IOException {
        synchronized (writeLock) {
            if (output == null) throw new IOException("控制通道未连接");
            output.write(frame);
            output.flush();
        }
    }

    private void readLoop() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
        byte[] chunk = new byte[1024];
        try {
            while (running) {
                int read = input.read(chunk);
                if (read < 0) break;
                if (read == 0) continue;
                buffer.write(chunk, 0, read);
                byte[] available = buffer.toByteArray();
                int consumed = parseAvailable(available);
                if (consumed > 0) {
                    buffer.reset();
                    buffer.write(available, consumed, available.length - consumed);
                }
            }
        } catch (Throwable t) {
            if (running) Log.d(TAG, "read loop ended: " + t);
        } finally {
            if (running) {
                state.connected = false;
                state.phase = "连接已断开";
                publish();
            }
        }
    }

    private int parseAvailable(byte[] data) {
        int offset = 0;
        while (data.length - offset >= 8) {
            int magic = findMagic(data, offset);
            if (magic < 0) {
                return Math.max(offset, data.length - 2);
            }
            offset = magic;
            if (data.length - offset < 8) return offset;
            int length = ((data[offset + 5] & 0xFF) << 8) | (data[offset + 6] & 0xFF);
            if (length > 8192) {
                offset++;
                continue;
            }
            int total = 8 + length;
            if (data.length - offset < total) return offset;
            int flags = data[offset + 3] & 0xFF;
            int opcode = data[offset + 4] & 0xFF;
            byte[] body = Arrays.copyOfRange(data, offset + 7, offset + 7 + length);
            offset += total;
            try {
                handlePacket(new RcspPacket(flags, opcode, body));
            } catch (Throwable t) {
                Log.w(TAG, "packet handling failed", t);
            }
        }
        return offset;
    }

    private static int findMagic(byte[] data, int start) {
        for (int i = start; i + 2 < data.length; i++) {
            if ((data[i] & 0xFF) == 0xFE
                    && (data[i + 1] & 0xFF) == 0xDC
                    && (data[i + 2] & 0xFF) == 0xBA) {
                return i;
            }
        }
        return -1;
    }

    private void handlePacket(RcspPacket packet) throws IOException {
        if (!packet.isCommand()) {
            if (packet.opcode == waitingOpcode && packet.sequence() == waitingSequence) {
                responses.offer(packet);
            }
            return;
        }

        int sn = packet.sequence();
        if (packet.opcode == 0x50) {
            byte[] data = packet.commandData();
            if (data.length >= 17 && data[0] == 0x01) {
                byte[] challenge = Arrays.copyOfRange(data, 1, 17);
                byte[] result = XiaomiAuth.transform(challenge);
                byte[] body = new byte[19];
                body[0] = 0;
                body[1] = (byte) sn;
                body[2] = 1;
                System.arraycopy(result, 0, body, 3, 16);
                writeFrame(RcspPacket.frame(FLAGS_RESPONSE, 0x50, body));
            }
        } else if (packet.opcode == 0x51) {
            byte[] data = packet.commandData();
            byte[] body = {0x00, (byte) sn, 0x01};
            writeFrame(RcspPacket.frame(FLAGS_RESPONSE, 0x51, body));
            if (data.length >= 2 && data[1] == 0x00) {
                devicePairDone.countDown();
            }
        } else if (packet.opcode == 0xF4) {
            byte[] data = packet.commandData();
            if (data.length > 0) {
                state.configs.putAll(parseConfigs(data));
                publish();
            }
            writeFrame(RcspPacket.frame(FLAGS_RESPONSE, 0xF4,
                    new byte[] {0x00, (byte) sn}));
        } else if (packet.wantsResponse()) {
            writeFrame(RcspPacket.frame(FLAGS_RESPONSE, packet.opcode,
                    new byte[] {0x00, (byte) sn}));
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static String friendly(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("read failed")
                || lower.contains("socket might closed")
                || lower.contains("bt socket closed")) {
            return "控制通道被占用，请停用小米耳机 App";
        }
        return message;
    }

    private void closeSocket() {
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        input = null;
        output = null;
        socket = null;
        state.connected = false;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        commands.clear();
        closeSocket();
        if (worker != null) worker.interrupt();
        if (reader != null) reader.interrupt();
        state.connected = false;
        state.phase = "已断开";
    }
}
