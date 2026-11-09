package io.github.xiaomipods.coloros;

import java.io.ByteArrayOutputStream;

final class RcspPacket {
    static final int MAGIC_1 = 0xFE;
    static final int MAGIC_2 = 0xDC;
    static final int MAGIC_3 = 0xBA;
    static final int END = 0xEF;

    final int flags;
    final int opcode;
    final byte[] body;

    RcspPacket(int flags, int opcode, byte[] body) {
        this.flags = flags;
        this.opcode = opcode;
        this.body = body;
    }

    boolean isCommand() {
        return (flags & 0x80) != 0;
    }

    boolean wantsResponse() {
        return (flags & 0x40) != 0;
    }

    int targetApp() {
        return flags & 0x07;
    }

    int sequence() {
        if (isCommand()) {
            return body.length > 0 ? body[0] & 0xFF : -1;
        }
        return body.length > 1 ? body[1] & 0xFF : -1;
    }

    int status() {
        return body.length > 0 ? body[0] & 0xFF : -1;
    }

    byte[] responseData() {
        return body.length > 2
                ? java.util.Arrays.copyOfRange(body, 2, body.length)
                : new byte[0];
    }

    byte[] commandData() {
        return body.length > 1
                ? java.util.Arrays.copyOfRange(body, 1, body.length)
                : new byte[0];
    }

    static byte[] frame(int flags, int opcode, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + body.length);
        out.write(MAGIC_1);
        out.write(MAGIC_2);
        out.write(MAGIC_3);
        out.write(flags & 0xFF);
        out.write(opcode & 0xFF);
        out.write((body.length >>> 8) & 0xFF);
        out.write(body.length & 0xFF);
        out.write(body, 0, body.length);
        out.write(END);
        return out.toByteArray();
    }
}
