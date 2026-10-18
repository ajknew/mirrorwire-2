package com.example.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages capture configuration parameters according to Section 6 specifications.
 * Bitrates are converted with exact integer bits per second:
 * 1.25 Mbps -> 1,250,000 bps
 * 1.50 Mbps -> 1,500,000 bps
 * 2.25 Mbps -> 2,250,000 bps, etc.
 */
public class CaptureSettings {
    private static final String PREF_NAME = "mirrorwire_capture_settings";
    private static final String KEY_RES_WIDTH = "res_width";
    private static final String KEY_RES_HEIGHT = "res_height";
    private static final String KEY_BITRATE_BPS = "bitrate_bps";
    private static final String KEY_HOST_FPS = "host_fps";
    private static final String KEY_ENABLE_LOOP = "enable_loop";

    public static class ResolutionOption {
        public final int width;
        public final int height;
        public final String label;
        public final boolean isLandscape;

        public ResolutionOption(int width, int height, String label, boolean isLandscape) {
            this.width = width;
            this.height = height;
            this.label = label;
            this.isLandscape = isLandscape;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final ResolutionOption[] RESOLUTION_OPTIONS = new ResolutionOption[] {
            // Landscape
            new ResolutionOption(1920, 1080, "1920x1080 (1080p 16:9 Landscape)", true),
            new ResolutionOption(1600, 900,  "1600x900 (900p 16:9 Landscape)", true),
            new ResolutionOption(1280, 720,  "1280x720 (720p HD Landscape - Recommended)", true),
            new ResolutionOption(1024, 576,  "1024x576 (576p 16:9 Landscape)", true),
            new ResolutionOption(854, 480,   "854x480 (480p SD Landscape)", true),
            new ResolutionOption(640, 360,   "640x360 (360p Low-Bandwidth Landscape)", true),
            // Portrait
            new ResolutionOption(1080, 1920, "1080x1920 (1080p 9:16 Portrait)", false),
            new ResolutionOption(900, 1600,  "900x1600 (900p 9:16 Portrait)", false),
            new ResolutionOption(720, 1280,  "720x1280 (720p HD Portrait)", false),
            new ResolutionOption(576, 1024,  "576x1024 (576p 9:16 Portrait)", false),
            new ResolutionOption(480, 854,   "480x854 (480p SD Portrait)", false),
            new ResolutionOption(360, 640,   "360x640 (360p Low-Bandwidth Portrait)", false),
    };

    public static class BitrateOption {
        public final String label;
        public final int bps;

        public BitrateOption(String label, int bps) {
            this.label = label;
            this.bps = bps;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final BitrateOption[] BITRATE_OPTIONS = new BitrateOption[] {
            new BitrateOption("1 Mbps", 1_000_000),
            new BitrateOption("1.25 Mbps", 1_250_000),
            new BitrateOption("1.50 Mbps", 1_500_000),
            new BitrateOption("1.75 Mbps", 1_750_000),
            new BitrateOption("2 Mbps", 2_000_000),
            new BitrateOption("2.25 Mbps", 2_250_000),
            new BitrateOption("2.50 Mbps", 2_500_000),
            new BitrateOption("2.75 Mbps", 2_750_000),
            new BitrateOption("3 Mbps", 3_000_000),
            new BitrateOption("3.5 Mbps", 3_500_000),
            new BitrateOption("4 Mbps", 4_000_000),
            new BitrateOption("5 Mbps", 5_000_000),
            new BitrateOption("6 Mbps", 6_000_000),
            new BitrateOption("8 Mbps", 8_000_000),
    };

    public static final int[] HOST_FPS_OPTIONS = new int[] { 15, 24, 30, 45, 60 };

    private int mWidth = 1280;
    private int mHeight = 720;
    private int mBitrateBps = 3_000_000;
    private int mHostFps = 60;
    private boolean mEnableContinuousLoop = true;

    private final SharedPreferences mPrefs;

    public CaptureSettings(Context context) {
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        mWidth = mPrefs.getInt(KEY_RES_WIDTH, 1280);
        mHeight = mPrefs.getInt(KEY_RES_HEIGHT, 720);
        mBitrateBps = mPrefs.getInt(KEY_BITRATE_BPS, 3_000_000);
        mHostFps = mPrefs.getInt(KEY_HOST_FPS, 60);
        mEnableContinuousLoop = mPrefs.getBoolean(KEY_ENABLE_LOOP, true);
    }

    public void save() {
        mPrefs.edit()
                .putInt(KEY_RES_WIDTH, mWidth)
                .putInt(KEY_RES_HEIGHT, mHeight)
                .putInt(KEY_BITRATE_BPS, mBitrateBps)
                .putInt(KEY_HOST_FPS, mHostFps)
                .putBoolean(KEY_ENABLE_LOOP, mEnableContinuousLoop)
                .apply();
    }

    /**
     * Builds the exact screenrecord shell command to be dispatched via ADB to CLIENT.
     */
    public String buildScreenrecordCommand() {
        if (mEnableContinuousLoop) {
            // Android screenrecord default stops at 180s on earlier Android versions.
            // Using a shell while loop guarantees seamless continuous mirroring.
            return String.format(
                    "shell:while true; do screenrecord --output-format=h264 --size %dx%d --bit-rate %d --time-limit 180 -; done",
                    mWidth, mHeight, mBitrateBps);
        } else {
            return String.format(
                    "shell:screenrecord --output-format=h264 --size %dx%d --bit-rate %d -",
                    mWidth, mHeight, mBitrateBps);
        }
    }

    public int getWidth() { return mWidth; }
    public void setWidth(int width) { mWidth = width; }

    public int getHeight() { return mHeight; }
    public void setHeight(int height) { mHeight = height; }

    public int getBitrateBps() { return mBitrateBps; }
    public void setBitrateBps(int bitrateBps) { mBitrateBps = bitrateBps; }

    public int getHostFps() { return mHostFps; }
    public void setHostFps(int hostFps) { mHostFps = hostFps; }

    public boolean isEnableContinuousLoop() { return mEnableContinuousLoop; }
    public void setEnableContinuousLoop(boolean enable) { mEnableContinuousLoop = enable; }
}
