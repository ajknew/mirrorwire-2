package com.example.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Encapsulates an ADB protocol packet header and payload.
 * ADB message header is exactly 24 bytes in little-endian format:
 * - command (4 bytes)
 * - arg0 (4 bytes)
 * - arg1 (4 bytes)
 * - data_length (4 bytes)
 * - data_check (4 bytes)
 * - magic (4 bytes) = command ^ 0xFFFFFFFF
 */
public class AdbMessage {
    public static final int HEADER_LENGTH = 24;

    public static final int A_SYNC = 0x434e5953; // "SYNC"
    public static final int A_CNXN = 0x4e584e43; // "CNXN"
    public static final int A_OPEN = 0x4e45504f; // "OPEN"
    public static final int A_OKAY = 0x59414b4f; // "OKAY"
    public static final int A_CLSE = 0x45534c43; // "CLSE"
    public static final int A_WRTE = 0x45545257; // "WRTE"
    public static final int A_AUTH = 0x48545541; // "AUTH"

    public static final int A_VERSION = 0x01000000;
    public static final int MAX_PAYLOAD = 256 * 1024; // 256 KB

    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSAPUBLICKEY = 3;

    private final int mCommand;
    private final int mArg0;
    private final int mArg1;
    private final int mDataLength;
    private final int mDataCheck;
    private final int mMagic;
    private byte[] mData;

    public AdbMessage(int command, int arg0, int arg1, int dataLength, int dataCheck, int magic, byte[] data) {
        mCommand = command;
        mArg0 = arg0;
        mArg1 = arg1;
        mDataLength = dataLength;
        mDataCheck = dataCheck;
        mMagic = magic;
        mData = data;
    }

    public static AdbMessage create(int command, int arg0, int arg1, byte[] data) {
        int length = (data != null) ? data.length : 0;
        int checksum = (data != null) ? calculateChecksum(data) : 0;
        int magic = command ^ 0xFFFFFFFF;
        return new AdbMessage(command, arg0, arg1, length, checksum, magic, data);
    }

    public static AdbMessage createCnxn(int maxPayload, String systemIdentity) {
        byte[] bannerBytes = (systemIdentity + "\0").getBytes(StandardCharsets.UTF_8);
        return create(A_CNXN, A_VERSION, maxPayload, bannerBytes);
    }

    public static AdbMessage createAuth(int authType, byte[] payload) {
        return create(A_AUTH, authType, 0, payload);
    }

    public static AdbMessage createOpen(int localId, String destination) {
        byte[] destBytes = (destination.endsWith("\0") ? destination : destination + "\0").getBytes(StandardCharsets.UTF_8);
        return create(A_OPEN, localId, 0, destBytes);
    }

    public static AdbMessage createOkay(int localId, int remoteId) {
        return create(A_OKAY, localId, remoteId, null);
    }

    public static AdbMessage createClse(int localId, int remoteId) {
        return create(A_CLSE, localId, remoteId, null);
    }

    public static AdbMessage createWrte(int localId, int remoteId, byte[] payload) {
        return create(A_WRTE, localId, remoteId, payload);
    }

    public static AdbMessage parseHeader(byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length < HEADER_LENGTH) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(headerBytes, 0, HEADER_LENGTH);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        int command = bb.getInt();
        int arg0 = bb.getInt();
        int arg1 = bb.getInt();
        int dataLength = bb.getInt();
        int dataCheck = bb.getInt();
        int magic = bb.getInt();

        return new AdbMessage(command, arg0, arg1, dataLength, dataCheck, magic, null);
    }

    public byte[] serializeHeader() {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_LENGTH);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(mCommand);
        bb.putInt(mArg0);
        bb.putInt(mArg1);
        bb.putInt(mDataLength);
        bb.putInt(mDataCheck);
        bb.putInt(mMagic);
        return bb.array();
    }

    public static int calculateChecksum(byte[] data) {
        if (data == null) {
            return 0;
        }
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF);
        }
        return sum;
    }

    public boolean validate() {
        if (mMagic != (mCommand ^ 0xFFFFFFFF)) {
            return false;
        }
        if (mDataLength > 0 && mData != null) {
            if (mData.length != mDataLength) {
                return false;
            }
            return calculateChecksum(mData) == mDataCheck;
        }
        return true;
    }

    public int getCommand() { return mCommand; }
    public int getArg0() { return mArg0; }
    public int getArg1() { return mArg1; }
    public int getDataLength() { return mDataLength; }
    public int getDataCheck() { return mDataCheck; }
    public int getMagic() { return mMagic; }
    public byte[] getData() { return mData; }
    public void setData(byte[] data) { mData = data; }

    public String getCommandName() {
        switch (mCommand) {
            case A_CNXN: return "CNXN";
            case A_AUTH: return "AUTH";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE";
            case A_WRTE: return "WRTE";
            case A_SYNC: return "SYNC";
            default: return String.format("0x%08X", mCommand);
        }
    }

    @Override
    public String toString() {
        return String.format("AdbMessage{%s, arg0=%d, arg1=%d, len=%d}",
                getCommandName(), mArg0, mArg1, mDataLength);
    }
}
