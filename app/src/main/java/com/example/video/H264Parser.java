package com.example.video;

import com.example.log.SystemLogger;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Robust H.264 Annex-B stream parser and Access Unit assembler.
 * Handles fragmented USB/ADB packets without dropping partial data,
 * extracts SPS/PPS parameter sets, detects video dimensions,
 * and emits complete access units for MediaCodec decoding.
 */
public class H264Parser {
    public static final int NAL_TYPE_SLICE = 1;     // P/B frame slice
    public static final int NAL_TYPE_IDR = 5;       // I-frame (IDR) slice
    public static final int NAL_TYPE_SEI = 6;       // Supplemental enhancement info
    public static final int NAL_TYPE_SPS = 7;       // Sequence parameter set
    public static final int NAL_TYPE_PPS = 8;       // Picture parameter set
    public static final int NAL_TYPE_AUD = 9;       // Access unit delimiter

    public interface FrameCallback {
        void onSpsReceived(byte[] spsBytes, SpsParser.SpsInfo info);
        void onPpsReceived(byte[] ppsBytes);
        void onAccessUnitReady(byte[] accessUnitData, boolean isKeyFrame);
    }

    private final FrameCallback mCallback;

    // Stream accumulator buffer
    private byte[] mBuffer = new byte[512 * 1024]; // 512 KB initial capacity
    private int mBufferLength = 0;

    // Current access unit accumulator
    private final ByteArrayOutputStream mAuStream = new ByteArrayOutputStream(256 * 1024);
    private boolean mCurrentAuHasSlice = false;
    private boolean mCurrentAuIsKeyframe = false;

    private byte[] mLastSps = null;
    private byte[] mLastPps = null;
    private SpsParser.SpsInfo mSpsInfo = null;

    public H264Parser(FrameCallback callback) {
        mCallback = callback;
    }

    /**
     * Feeds raw bytes received from an ADB packet into the parser.
     */
    public synchronized void feedData(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return;
        }

        // Ensure buffer has capacity
        if (mBufferLength + length > mBuffer.length) {
            int newCap = Math.max(mBuffer.length * 2, mBufferLength + length + 256 * 1024);
            mBuffer = Arrays.copyOf(mBuffer, newCap);
        }

        System.arraycopy(data, offset, mBuffer, mBufferLength, length);
        mBufferLength += length;

        parseBuffer();
    }

    private void parseBuffer() {
        int searchPos = 0;
        int prevStartCodePos = -1;
        int prevStartCodeLen = 0;

        while (true) {
            int startCodePos = findNextStartCode(mBuffer, searchPos, mBufferLength);
            if (startCodePos < 0) {
                break;
            }

            int startCodeLen = (mBuffer[startCodePos + 2] == 0x01) ? 3 : 4;

            if (prevStartCodePos >= 0) {
                // We have a complete NAL unit between prevStartCodePos and startCodePos
                int nalLength = startCodePos - prevStartCodePos;
                processNalUnit(mBuffer, prevStartCodePos, nalLength, prevStartCodeLen);
            }

            prevStartCodePos = startCodePos;
            prevStartCodeLen = startCodeLen;
            searchPos = startCodePos + startCodeLen;
        }

        // Shift remaining unparsed bytes (from prevStartCodePos) to start of buffer
        if (prevStartCodePos >= 0) {
            int remaining = mBufferLength - prevStartCodePos;
            System.arraycopy(mBuffer, prevStartCodePos, mBuffer, 0, remaining);
            mBufferLength = remaining;
        } else {
            // No start code found in buffer, but keep last 3 bytes in case a start code is split across packets
            if (mBufferLength > 3) {
                int shift = mBufferLength - 3;
                System.arraycopy(mBuffer, shift, mBuffer, 0, 3);
                mBufferLength = 3;
            }
        }
    }

    private void processNalUnit(byte[] buffer, int nalOffset, int nalLength, int startCodeLen) {
        if (nalLength <= startCodeLen) {
            return;
        }

        int nalHeaderPos = nalOffset + startCodeLen;
        int nalType = buffer[nalHeaderPos] & 0x1F;

        if (nalType == NAL_TYPE_SPS) {
            byte[] sps = new byte[nalLength];
            System.arraycopy(buffer, nalOffset, sps, 0, nalLength);
            mLastSps = sps;
            mSpsInfo = SpsParser.parseSps(buffer, nalHeaderPos, nalLength - startCodeLen);
            if (mSpsInfo != null) {
                SystemLogger.getInstance().log("DECODER", "Parsed SPS dimensions: " + mSpsInfo);
            }
            if (mCallback != null) {
                mCallback.onSpsReceived(sps, mSpsInfo);
            }
        } else if (nalType == NAL_TYPE_PPS) {
            byte[] pps = new byte[nalLength];
            System.arraycopy(buffer, nalOffset, pps, 0, nalLength);
            mLastPps = pps;
            if (mCallback != null) {
                mCallback.onPpsReceived(pps);
            }
        }

        // Check if this NAL unit indicates a new Access Unit boundary
        boolean isSlice = (nalType == NAL_TYPE_SLICE || nalType == NAL_TYPE_IDR);
        boolean isAud = (nalType == NAL_TYPE_AUD);
        boolean isParamSet = (nalType == NAL_TYPE_SPS || nalType == NAL_TYPE_PPS);

        if (mCurrentAuHasSlice && (isSlice || isAud || isParamSet)) {
            // Complete previous access unit!
            emitCurrentAccessUnit();
        }

        // Append this NAL unit to current Access Unit
        mAuStream.write(buffer, nalOffset, nalLength);

        if (nalType == NAL_TYPE_IDR) {
            mCurrentAuIsKeyframe = true;
            mCurrentAuHasSlice = true;
        } else if (nalType == NAL_TYPE_SLICE) {
            mCurrentAuHasSlice = true;
        }
    }

    private void emitCurrentAccessUnit() {
        if (mAuStream.size() == 0) {
            return;
        }

        byte[] auData = mAuStream.toByteArray();
        boolean keyframe = mCurrentAuIsKeyframe;

        mAuStream.reset();
        mCurrentAuHasSlice = false;
        mCurrentAuIsKeyframe = false;

        if (mCallback != null) {
            mCallback.onAccessUnitReady(auData, keyframe);
        }
    }

    private static int findNextStartCode(byte[] data, int start, int end) {
        int limit = end - 3;
        for (int i = start; i <= limit; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00) {
                if (data[i + 2] == 0x01) {
                    return i;
                }
                if (i <= end - 4 && data[i + 2] == 0x00 && data[i + 3] == 0x01) {
                    return i;
                }
            }
        }
        return -1;
    }

    public synchronized void flush() {
        emitCurrentAccessUnit();
    }

    public synchronized void reset() {
        mBufferLength = 0;
        mAuStream.reset();
        mCurrentAuHasSlice = false;
        mCurrentAuIsKeyframe = false;
        mLastSps = null;
        mLastPps = null;
        mSpsInfo = null;
    }

    public SpsParser.SpsInfo getSpsInfo() {
        return mSpsInfo;
    }

    public byte[] getLastSps() {
        return mLastSps;
    }

    public byte[] getLastPps() {
        return mLastPps;
    }
}
