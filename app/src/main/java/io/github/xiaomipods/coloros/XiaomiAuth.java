package io.github.xiaomipods.coloros;

/**
 * Xiaomi/JieLi RCSP authentication transform.
 *
 * Recovered from the Xiaomi Earphone SDK. The transform is independent of the
 * device MAC; the app always uses the fixed placeholder address below.
 */
final class XiaomiAuth {
    private static final byte[] ADDR6 = {
            0x11, 0x22, 0x33, 0x33, 0x22, 0x11
    };

    private static final String TAB_EXPAND_HEX =
            "64ac285ac9b337c50a10b7a3bab197463d05dc666ef69af80d589567c6aaabec"
            + "a0689b96d4ebbf434936e96a89d8c38a946399bc7bbec122bb5c71d51f92575d"
            + "8f44411d51e64017fbfd193234b8612aca236fda39f7a2017fd631e7de8004dd"
            + "2c5982afa8e00fcda1123e30d11cd03a33722e4f9002130675ce87c2efb2ad7d"
            + "3815e1529f7a6c2f27c4e281a9cf8dc0d7dfff6076148c5e5509e408c74220fc"
            + "d25091d94c629ee8b9a6f91a00210bfa359c4e4b6948cb0ec8a45bea8407b418"
            + "f4ae6bdba7cc3f8b4a0c3c25e5544d4583ed11f0b05393f27426b59d6d7cf32d"
            + "f156247e471b86bd708e1e3b731603b6ac285ac9b337c50a10b7a3bab1974688";

    private static final String TAB_A_HEX =
            "012de293be4515ae780387a4b838cf3f08670994eb26a86bbd18341bbbbf72f7"
            + "4035489c512f3b55e3c09fd8d3f38db1ffa73edc8677d7a611fbf4ba92916483"
            + "f133efda2cb5b22b88d199cb8c841d14819771ca5fa38b573c82c4525c1ce8a0"
            + "04b4854af61354b6df0c1a8edee039fc209b244ea9989eabf260d06ceafac7d9"
            + "00d41f6e43bcec5389fe7a5d49c932c2f99af86d16db599644e9cde646428f0a"
            + "c1ccb965b0d2c6ac1e4162292e0e7450025ac3257b8a2a5bf0060d476f709d7e"
            + "10ce1227d54c4fd679306836757de4ed806a9037a25e76aac57f3dafa5e51961"
            + "fd4d7cb70beead4b22f5e7732321c805e166ddb3586963560fa1319517073a28";

    private static final String TAB_B_HEX =
            "8000b00960efb9fd10129fe469baadf8c038c2654f0694fc19de6a1b5d4ea882"
            + "70ede8ec72b315c3ffabb6474401ac25c9fa8e411a21cbd30d6efe2658da320f"
            + "20a99d8498059cbb228c63e7c5e173c6af245b876627f757f496b1b75c8bd554"
            + "79dfaaf63ea3f111caf5d1177b9383bcbd521eebaeccd63508c88ab4e2cdbfd9"
            + "d050593f4d62340a4888b5564c2e6b9ed23d3c0313fb9751754a917123be762a"
            + "5ff9d4550bdc37311674d777a7e607dba42f46f3614567e30ca23b1c8518041d"
            + "29a08fb25ad8a67eee8d534ba19ac10e7a49a52c81c4c7362b7f439533f26c68"
            + "6df00228cedd9bea5e997c1486cfe542b840782d3ae9641f92907d396fe08930";

    private static final int[] TAB_EXPAND = hex(TAB_EXPAND_HEX);
    private static final int[] TAB_A = hex(TAB_A_HEX);
    private static final int[] TAB_B = hex(TAB_B_HEX);
    private static final int XOR_MASK = 0x9999;

    private XiaomiAuth() {}

    static byte[] transform(byte[] challenge) {
        if (challenge == null || challenge.length != 16) {
            throw new IllegalArgumentException("challenge must be 16 bytes");
        }
        byte[] state = new byte[16];
        System.arraycopy(ADDR6, 0, state, 0, 6);
        System.arraycopy(ADDR6, 0, state, 6, 6);
        System.arraycopy(ADDR6, 0, state, 12, 4);

        byte[] tmp = challenge.clone();
        tmp[15] ^= 0x06;
        byte[] schedule = expandKey(tmp);
        return mix(state, schedule);
    }

    private static int[] hex(String value) {
        int[] output = new int[value.length() / 2];
        for (int i = 0; i < output.length; i++) {
            output[i] = Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return output;
    }

    private static int rol8(int value, int bits) {
        value &= 0xFF;
        return ((value << bits) | (value >>> (8 - bits))) & 0xFF;
    }

    private static boolean selected(int index) {
        return ((1 << index) & XOR_MASK) != 0;
    }

    private static byte[] expandKey(byte[] key) {
        byte[] buffer = new byte[272];
        System.arraycopy(key, 0, buffer, 0, 16);
        int checksum = 0;
        for (int i = 0; i < 8; i++) {
            checksum ^= (key[i] ^ key[i + 8]) & 0xFF;
        }
        int[] state = new int[16];
        for (int i = 0; i < 16; i++) state[i] = key[i] & 0xFF;

        for (int round = 0; round < 16; round++) {
            for (int i = 0; i < 16; i++) state[i] = rol8(state[i], 3);
            checksum = rol8(checksum, 3);
            int base = round * 16;
            int outputBase = 16 + round * 16;
            for (int j = 0; j < 16; j++) {
                int windowIndex = (round + 1 + j) % 17;
                int window = windowIndex < 16 ? state[windowIndex] : checksum;
                buffer[outputBase + j] =
                        (byte) ((TAB_EXPAND[base + 15 - j] + window) & 0xFF);
            }
        }
        return buffer;
    }

    private static byte[] mix(byte[] state, byte[] schedule) {
        int[] out = new int[16];
        int[] original = new int[16];
        for (int i = 0; i < 16; i++) {
            out[i] = state[i] & 0xFF;
            original[i] = out[i];
        }

        for (int round = 0; round < 8; round++) {
            int base = round * 32;
            if (round == 2) {
                for (int i = 0; i < 16; i++) {
                    out[i] = selected(i) ? out[i] ^ original[i] : (out[i] + original[i]) & 0xFF;
                }
            }
            for (int i = 0; i < 16; i++) {
                int value = schedule[base + i] & 0xFF;
                out[i] = selected(i) ? out[i] ^ value : (out[i] + value) & 0xFF;
            }
            for (int i = 0; i < 16; i++) {
                int value = out[i] & 0xFF;
                out[i] = selected(i) ? TAB_A[value] : TAB_B[value];
            }
            for (int i = 0; i < 16; i++) {
                int value = schedule[base + 16 + i] & 0xFF;
                out[i] = selected(i) ? (out[i] + value) & 0xFF : out[i] ^ value;
            }
            out = round16(out);
        }

        for (int i = 0; i < 16; i++) {
            int value = schedule[0x100 + i] & 0xFF;
            out[i] = selected(i) ? out[i] ^ value : (out[i] + value) & 0xFF;
        }

        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) result[i] = (byte) out[i];
        return result;
    }

    private static int[] round16(int[] input) {
        int b0 = input[0], b1 = input[1], b2 = input[2], b3 = input[3];
        int b4 = input[4], b5 = input[5], b6 = input[6], b7 = input[7];
        int b8 = input[8], b9 = input[9], b10 = input[10], b11 = input[11];
        int b12 = input[12], b13 = input[13], b14 = input[14], b15 = input[15];

        int w7 = b1 + 2 * b0;
        int w16 = b1 + b0;
        int w20 = b3 + 2 * b2;
        int w3 = b3 + b2;
        int w22 = b5 + 2 * b4;
        int w5 = b5 + b4;
        int w24 = b7 + 2 * b6;
        int w17 = b7 + b6;
        int w26 = b9 + 2 * b8;
        int w4 = b9 + b8;
        int w28 = b11 + 2 * b10;
        int w6 = b11 + b10;
        int w23 = b13 + 2 * b12;
        int w19 = b13 + b12;
        int w25 = b15 + 2 * b14;
        int w21 = b15 + b14;

        int w27 = w6 + 2 * w26; w6 = w6 + w26;
        w26 = w21 + 2 * w23; w21 = w21 + w23;
        w23 = w16 + 2 * w20; w16 = w20 + w16;
        w20 = w5 + 2 * w24; w5 = w24 + w5;
        w24 = w4 + 2 * w28; w4 = w28 + w4;
        w28 = w19 + 2 * w25; w19 = w25 + w19;
        w25 = w17 + 2 * w7; w17 = w17 + w7;
        w7 = w3 + 2 * w22; w3 = w22 + w3;
        w22 = w19 + 2 * w24; w19 = w19 + w24;
        w24 = w3 + 2 * w25; w3 = w25 + w3;
        w25 = w16 + 2 * w20; w16 = w20 + w16;
        w20 = w4 + 2 * w28; w4 = w28 + w4;
        w28 = w17 + 2 * w7; w17 = w17 + w7;
        int w30 = w20 + w17; w17 = w17 + 2 * w20;
        w7 = w5 + 2 * w27; w5 = w27 + w5;
        w27 = w21 + 2 * w23; w21 = w21 + w23;

        int[] out = new int[16];
        out[0] = w17;
        w17 = w19 + w24;
        w23 = w26 + w6;
        w19 = w19 + 2 * w24;
        w20 = w21 + w7;
        w7 = w21 + 2 * w7;
        out[5] = w17;
        w17 = w23 + 2 * w25;
        out[4] = w19;
        w19 = w4 + w28;
        w4 = w4 + 2 * w28;
        out[2] = w7;
        out[6] = w17;
        w17 = w27 + w5;
        w7 = w23 + w25;
        w5 = w5 + 2 * w27;
        out[8] = w4;
        w4 = w22 + w16;
        w16 = w16 + 2 * w22;
        out[11] = w17;
        w17 = w6 + 2 * w26;
        out[1] = w30;
        out[13] = w4;
        w4 = w17 + w3;
        out[12] = w16;
        w16 = w3 + 2 * w17;
        out[3] = w20;
        out[7] = w7;
        out[9] = w19;
        out[10] = w5;
        out[15] = w4;
        out[14] = w16;
        for (int i = 0; i < 16; i++) out[i] &= 0xFF;
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02X", b & 0xFF));
        return out.toString();
    }
}
