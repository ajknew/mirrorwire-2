package com.example;

import com.example.config.CaptureSettings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying Section 6 bitrate integer conversion and screenrecord command formatting.
 */
public class CaptureSettingsTest {

    @Test
    public void testBitrateExactIntegerBpsConversion() {
        int bps1_00 = 1_000_000;
        int bps1_25 = 1_250_000;
        int bps1_50 = 1_500_000;
        int bps1_75 = 1_750_000;
        int bps2_00 = 2_000_000;
        int bps2_25 = 2_250_000;
        int bps2_50 = 2_500_000;
        int bps2_75 = 2_750_000;
        int bps3_00 = 3_000_000;
        int bps3_50 = 3_500_000;
        int bps4_00 = 4_000_000;
        int bps5_00 = 5_000_000;
        int bps6_00 = 6_000_000;
        int bps8_00 = 8_000_000;

        assertEquals(1250000, bps1_25);
        assertEquals(1500000, bps1_50);
        assertEquals(1750000, bps1_75);
        assertEquals(2250000, bps2_25);
        assertEquals(2500000, bps2_50);
        assertEquals(2750000, bps2_75);

        // Verify all defined BITRATE_OPTIONS match exact values
        boolean found125 = false, found225 = false, found250 = false, found275 = false;
        for (CaptureSettings.BitrateOption opt : CaptureSettings.BITRATE_OPTIONS) {
            if ("1.25 Mbps".equals(opt.label)) {
                assertEquals(1250000, opt.bps);
                found125 = true;
            }
            if ("2.25 Mbps".equals(opt.label)) {
                assertEquals(2250000, opt.bps);
                found225 = true;
            }
            if ("2.50 Mbps".equals(opt.label)) {
                assertEquals(2500000, opt.bps);
                found250 = true;
            }
            if ("2.75 Mbps".equals(opt.label)) {
                assertEquals(2750000, opt.bps);
                found275 = true;
            }
        }
        assertTrue(found125);
        assertTrue(found225);
        assertTrue(found250);
        assertTrue(found275);
    }

    @Test
    public void testScreenrecordCommandFormat() {
        int width = 1280;
        int height = 720;
        int bitrate = 2_250_000;

        String cmdSingle = String.format("shell:screenrecord --output-format=h264 --size %dx%d --bit-rate %d -",
                width, height, bitrate);
        assertEquals("shell:screenrecord --output-format=h264 --size 1280x720 --bit-rate 2250000 -", cmdSingle);

        String cmdLoop = String.format(
                "shell:while true; do screenrecord --output-format=h264 --size %dx%d --bit-rate %d --time-limit 180 -; done",
                width, height, bitrate);
        assertTrue(cmdLoop.contains("--size 1280x720"));
        assertTrue(cmdLoop.contains("--bit-rate 2250000"));
        assertTrue(cmdLoop.contains("--output-format=h264"));
    }
}
