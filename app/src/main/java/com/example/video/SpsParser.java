package com.example.video;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Robust H.264 Sequence Parameter Set (SPS) parser.
 * Unescapes emulation prevention bytes (0x00 0x00 0x03 -> 0x00 0x00)
 * and reads Exp-Golomb coded syntax elements to extract true video dimensions.
 */
public class SpsParser {

    public static class SpsInfo {
        public final int width;
        public final int height;
        public final int profileIdc;
        public final int levelIdc;

        public SpsInfo(int width, int height, int profileIdc, int levelIdc) {
            this.width = width;
            this.height = height;
            this.profileIdc = profileIdc;
            this.levelIdc = levelIdc;
        }

        @Override
        public String toString() {
            return String.format("%dx%d (Profile %d, Level %d)", width, height, profileIdc, levelIdc);
        }
    }

    /**
     * Parses an SPS NAL unit buffer (excluding start code and NAL header byte if passed directly).
     */
    public static SpsInfo parseSps(byte[] data, int offset, int length) {
        try {
            // 1. Strip emulation prevention 0x03 bytes
            byte[] unescaped = unescapeEmulationPrevention(data, offset, length);
            BitReader reader = new BitReader(unescaped);

            // Read NAL unit header (1 byte: forbidden_zero_bit=1, nal_ref_idc=2, nal_unit_type=5)
            int nalHeader = reader.readBits(8);
            int nalUnitType = nalHeader & 0x1F;
            if (nalUnitType != 7) {
                // Not an SPS
                return null;
            }

            int profileIdc = reader.readBits(8);
            reader.readBits(8); // constraint_set_flags + reserved
            int levelIdc = reader.readBits(8);
            reader.readUe(); // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                int chromaFormatIdc = reader.readUe();
                if (chromaFormatIdc == 3) {
                    reader.readBits(1); // separate_colour_plane_flag
                }
                reader.readUe(); // bit_depth_luma_minus8
                reader.readUe(); // bit_depth_chroma_minus8
                reader.readBits(1); // qpprime_y_zero_transform_bypass_flag
                boolean seqScalingMatrixPresent = reader.readBoolean();
                if (seqScalingMatrixPresent) {
                    int count = (chromaFormatIdc != 3) ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        boolean seqScalingListPresent = reader.readBoolean();
                        if (seqScalingListPresent) {
                            skipScalingList(reader, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            reader.readUe(); // log2_max_frame_num_minus4
            int picOrderCntType = reader.readUe();
            if (picOrderCntType == 0) {
                reader.readUe(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBits(1); // delta_pic_order_always_zero_flag
                reader.readSe(); // offset_for_non_ref_pic
                reader.readSe(); // offset_for_top_to_bottom_field
                int numRefFramesInPicOrderCntCycle = reader.readUe();
                for (int i = 0; i < numRefFramesInPicOrderCntCycle; i++) {
                    reader.readSe(); // offset_for_ref_frame[i]
                }
            }

            reader.readUe(); // max_num_ref_frames
            reader.readBits(1); // gaps_in_frame_num_value_allowed_flag
            int picWidthInMbsMinus1 = reader.readUe();
            int picHeightInMapUnitsMinus1 = reader.readUe();
            boolean frameMbsOnlyFlag = reader.readBoolean();
            if (!frameMbsOnlyFlag) {
                reader.readBits(1); // mb_adaptive_frame_field_flag
            }
            reader.readBits(1); // direct_8x8_inference_flag

            boolean frameCroppingFlag = reader.readBoolean();
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (frameCroppingFlag) {
                cropLeft = reader.readUe();
                cropRight = reader.readUe();
                cropTop = reader.readUe();
                cropBottom = reader.readUe();
            }

            int width = (picWidthInMbsMinus1 + 1) * 16;
            int height = (picHeightInMapUnitsMinus1 + 1) * 16 * (frameMbsOnlyFlag ? 1 : 2);

            int cropUnitX = 2; // Assuming 4:2:0
            int cropUnitY = 2 * (frameMbsOnlyFlag ? 1 : 2);
            width -= (cropLeft + cropRight) * cropUnitX;
            height -= (cropTop + cropBottom) * cropUnitY;

            return new SpsInfo(width, height, profileIdc, levelIdc);
        } catch (Exception e) {
            return null;
        }
    }

    private static void skipScalingList(BitReader reader, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = reader.readSe();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    private static byte[] unescapeEmulationPrevention(byte[] src, int offset, int length) {
        byte[] dest = new byte[length];
        int destPos = 0;
        int zeros = 0;

        for (int i = 0; i < length; i++) {
            byte b = src[offset + i];
            if (zeros == 2 && b == 0x03) {
                // Skip emulation prevention byte
                zeros = 0;
                continue;
            }
            dest[destPos++] = b;
            if (b == 0x00) {
                zeros++;
            } else {
                zeros = 0;
            }
        }

        byte[] result = new byte[destPos];
        System.arraycopy(dest, 0, result, 0, destPos);
        return result;
    }

    private static class BitReader {
        private final byte[] mBuffer;
        private int mBitPos = 0;

        public BitReader(byte[] buffer) {
            mBuffer = buffer;
        }

        public int readBits(int numBits) {
            int result = 0;
            for (int i = 0; i < numBits; i++) {
                int bytePos = mBitPos / 8;
                int bitOffset = 7 - (mBitPos % 8);
                if (bytePos < mBuffer.length) {
                    int bit = (mBuffer[bytePos] >> bitOffset) & 1;
                    result = (result << 1) | bit;
                }
                mBitPos++;
            }
            return result;
        }

        public boolean readBoolean() {
            return readBits(1) != 0;
        }

        public int readUe() {
            int leadingZeroBits = 0;
            while (readBits(1) == 0 && leadingZeroBits < 32) {
                leadingZeroBits++;
            }
            if (leadingZeroBits == 0) {
                return 0;
            }
            int suffix = readBits(leadingZeroBits);
            return (1 << leadingZeroBits) - 1 + suffix;
        }

        public int readSe() {
            int codeNum = readUe();
            int sign = ((codeNum & 1) == 0) ? -1 : 1;
            return sign * ((codeNum + 1) / 2);
        }
    }
}
