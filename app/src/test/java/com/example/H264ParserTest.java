package com.example;

import com.example.video.H264Parser;
import com.example.video.SpsParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying H.264 stream framing, start-code detection,
 * multi-packet fragmentation handling, and Access Unit assembly.
 */
public class H264ParserTest {

    @Test
    public void testStartCodeAndNalExtraction() {
        final List<byte[]> receivedAus = new ArrayList<>();
        final List<Boolean> keyframes = new ArrayList<>();

        H264Parser parser = new H264Parser(new H264Parser.FrameCallback() {
            @Override
            public void onSpsReceived(byte[] spsBytes, SpsParser.SpsInfo info) {}

            @Override
            public void onPpsReceived(byte[] ppsBytes) {}

            @Override
            public void onAccessUnitReady(byte[] accessUnitData, boolean isKeyFrame) {
                receivedAus.add(accessUnitData);
                keyframes.add(isKeyFrame);
            }
        });

        // Frame 1: IDR Slice (00 00 00 01 65 [NAL Type 5])
        byte[] frame1 = new byte[] {
                0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33
        };
        // Frame 2: Non-IDR Slice (00 00 00 01 41 [NAL Type 1])
        byte[] frame2 = new byte[] {
                0x00, 0x00, 0x00, 0x01, 0x41, 0x44, 0x55, 0x66
        };
        // Frame 3: Non-IDR Slice
        byte[] frame3 = new byte[] {
                0x00, 0x00, 0x00, 0x01, 0x41, 0x77, (byte) 0x88, (byte) 0x99
        };

        // Feed frame 1
        parser.feedData(frame1, 0, frame1.length);
        // Feed frame 2
        parser.feedData(frame2, 0, frame2.length);
        // Feed frame 3 (triggers completion of frame 1)
        parser.feedData(frame3, 0, frame3.length);

        assertEquals(1, receivedAus.size());
        assertTrue(keyframes.get(0));

        // Flush parser to complete pending frames
        parser.flush();
        assertTrue(receivedAus.size() >= 2);
    }

    @Test
    public void testFragmentedPacketHandling() {
        final List<byte[]> receivedAus = new ArrayList<>();

        H264Parser parser = new H264Parser(new H264Parser.FrameCallback() {
            @Override public void onSpsReceived(byte[] spsBytes, SpsParser.SpsInfo info) {}
            @Override public void onPpsReceived(byte[] ppsBytes) {}
            @Override
            public void onAccessUnitReady(byte[] accessUnitData, boolean isKeyFrame) {
                receivedAus.add(accessUnitData);
            }
        });

        // Packet 1: Contains start code and first half of slice
        byte[] chunk1 = new byte[] { 0x00, 0x00, 0x00, 0x01, 0x65, 0x10, 0x20 };
        // Packet 2: Contains second half of slice
        byte[] chunk2 = new byte[] { 0x30, 0x40, 0x50 };
        // Packet 3: Contains next start code
        byte[] chunk3 = new byte[] { 0x00, 0x00, 0x00, 0x01, 0x41, 0x01 };

        parser.feedData(chunk1, 0, chunk1.length);
        parser.feedData(chunk2, 0, chunk2.length);
        assertEquals(0, receivedAus.size()); // Still waiting for boundary

        parser.feedData(chunk3, 0, chunk3.length);
        // Flush in-flight access unit
        parser.flush();
        assertEquals(1, receivedAus.size()); // Assembled across chunks 1 and 2!

        byte[] au = receivedAus.get(0);
        assertEquals(10, au.length); // 4 bytes start code + 6 payload bytes
        assertEquals(0x65, au[4]);
        assertEquals(0x50, au[9]);
    }
}
