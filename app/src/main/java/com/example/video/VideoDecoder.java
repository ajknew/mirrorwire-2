package com.example.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import com.example.log.SystemLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hardware accelerated H.264 MediaCodec decoder.
 * Submits decoded frames directly to the designated Surface.
 * Calculates real frame submission FPS and handles dynamic resolution adjustments.
 */
public class VideoDecoder {
    private static final String TAG = "DECODER";
    private static final String MIME_TYPE = "video/avc";

    public interface DecoderListener {
        void onDecoderStarted(int width, int height);
        void onFormatChanged(int newWidth, int newHeight);
        void onFpsUpdated(int fps, long totalFramesSubmitted);
        void onDecoderError(String error);
        void onDecoderStopped();
    }

    private final DecoderListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private MediaCodec mDecoder;
    private Surface mSurface;
    private int mWidth = 1280;
    private int mHeight = 720;
    private int mHostTargetFps = 60;

    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private Thread mOutputPumpThread;

    private long mTotalFramesSubmitted = 0;
    private long mLastFpsCalcTimeMs = 0;
    private int mFramesThisSecond = 0;
    private int mCurrentFps = 0;

    private long mLastFrameSubmitTimeNs = 0;

    public VideoDecoder(DecoderListener listener) {
        mListener = listener;
    }

    public synchronized boolean initAndStart(int width, int height, byte[] sps, byte[] pps, Surface surface, int targetFps) {
        stop();

        if (surface == null || !surface.isValid()) {
            SystemLogger.getInstance().log("ERROR", "Invalid or null Surface passed to VideoDecoder");
            postError("Surface is invalid");
            return false;
        }

        mWidth = (width > 0) ? width : 1280;
        mHeight = (height > 0) ? height : 720;
        mSurface = surface;
        mHostTargetFps = (targetFps > 0) ? targetFps : 60;

        try {
            SystemLogger.getInstance().log(TAG, String.format("Initializing MediaCodec for %s (%dx%d, Target FPS=%d)...",
                    MIME_TYPE, mWidth, mHeight, mHostTargetFps));

            mDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);

            if (sps != null && sps.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            }
            if (pps != null && pps.length > 0) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            }

            // Low-latency configuration on Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                } catch (Exception ignored) {}
            }

            mDecoder.configure(format, mSurface, null, 0);
            mDecoder.start();

            mIsRunning.set(true);
            mTotalFramesSubmitted = 0;
            mFramesThisSecond = 0;
            mLastFpsCalcTimeMs = SystemClock.uptimeMillis();
            mLastFrameSubmitTimeNs = 0;

            mOutputPumpThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    outputPumpLoop();
                }
            }, "MirrorWire-DecoderPump");
            mOutputPumpThread.start();

            SystemLogger.getInstance().log(TAG, "MediaCodec started successfully. Output pump running.");
            postStarted(mWidth, mHeight);
            return true;
        } catch (IOException e) {
            String err = "Failed to create MediaCodec: " + e.getMessage();
            SystemLogger.getInstance().log("ERROR", err);
            postError(err);
            return false;
        } catch (Exception e) {
            String err = "Error configuring MediaCodec: " + e.getMessage();
            SystemLogger.getInstance().log("ERROR", err);
            postError(err);
            return false;
        }
    }

    public synchronized void feedAccessUnit(byte[] data, boolean isKeyFrame) {
        if (!mIsRunning.get() || mDecoder == null) {
            return;
        }

        try {
            int inputBufferIndex = mDecoder.dequeueInputBuffer(10000); // 10ms timeout
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                } else {
                    inputBuffer = mDecoder.getInputBuffers()[inputBufferIndex];
                }

                if (inputBuffer != null) {
                    inputBuffer.clear();
                    if (data.length > inputBuffer.remaining()) {
                        SystemLogger.getInstance().log("ERROR", "Access Unit exceeds decoder input buffer capacity: " +
                                data.length + " > " + inputBuffer.remaining());
                        return;
                    }
                    inputBuffer.put(data);

                    long pts = System.nanoTime() / 1000; // microseconds
                    int flags = isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mDecoder.queueInputBuffer(inputBufferIndex, 0, data.length, pts, flags);
                }
            }
        } catch (IllegalStateException e) {
            SystemLogger.getInstance().log("ERROR", "Decoder queueInputBuffer IllegalStateException: " + e.getMessage());
        } catch (Exception e) {
            SystemLogger.getInstance().log("ERROR", "Decoder input feed error: " + e.getMessage());
        }
    }

    private void outputPumpLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long minFrameDeltaNs = (mHostTargetFps > 0) ? (1_000_000_000L / mHostTargetFps) : 0;

        while (mIsRunning.get() && mDecoder != null) {
            try {
                int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 10000); // 10ms
                if (outputBufferIndex >= 0) {
                    // Frame pacing check for host display dispatch
                    if (minFrameDeltaNs > 0) {
                        long nowNs = System.nanoTime();
                        long elapsedNs = nowNs - mLastFrameSubmitTimeNs;
                        if (elapsedNs < minFrameDeltaNs) {
                            long sleepMs = (minFrameDeltaNs - elapsedNs) / 1_000_000L;
                            if (sleepMs > 0 && sleepMs < 100) {
                                try {
                                    Thread.sleep(sleepMs);
                                } catch (InterruptedException ignored) {}
                            }
                        }
                        mLastFrameSubmitTimeNs = System.nanoTime();
                    }

                    // Release buffer to Surface (render=true)
                    mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                    mTotalFramesSubmitted++;
                    mFramesThisSecond++;

                    long nowMs = SystemClock.uptimeMillis();
                    if (nowMs - mLastFpsCalcTimeMs >= 1000) {
                        mCurrentFps = mFramesThisSecond;
                        mFramesThisSecond = 0;
                        mLastFpsCalcTimeMs = nowMs;
                        postFps(mCurrentFps, mTotalFramesSubmitted);
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mDecoder.getOutputFormat();
                    int w = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                    int h = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    mWidth = w;
                    mHeight = h;
                    SystemLogger.getInstance().log(TAG, String.format("Decoder output format changed: %dx%d", w, h));
                    postFormatChanged(w, h);
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Normal when no new frame is ready
                }
            } catch (IllegalStateException e) {
                if (mIsRunning.get()) {
                    SystemLogger.getInstance().log("ERROR", "Decoder output loop state exception: " + e.getMessage());
                }
                break;
            } catch (Exception e) {
                if (mIsRunning.get()) {
                    SystemLogger.getInstance().log("ERROR", "Decoder output loop exception: " + e.getMessage());
                }
                break;
            }
        }

        SystemLogger.getInstance().log(TAG, "Decoder output pump finished");
    }

    public synchronized void stop() {
        if (!mIsRunning.get() && mDecoder == null) {
            return;
        }

        mIsRunning.set(false);

        if (mOutputPumpThread != null) {
            mOutputPumpThread.interrupt();
            try {
                mOutputPumpThread.join(500);
            } catch (InterruptedException ignored) {}
            mOutputPumpThread = null;
        }

        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                SystemLogger.getInstance().log("ERROR", "Error releasing decoder: " + e.getMessage());
            }
            mDecoder = null;
        }

        mCurrentFps = 0;
        mFramesThisSecond = 0;
        postStopped();
    }

    public boolean isRunning() {
        return mIsRunning.get();
    }

    public int getCurrentFps() {
        return mCurrentFps;
    }

    public long getTotalFramesSubmitted() {
        return mTotalFramesSubmitted;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    private void postStarted(final int w, final int h) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onDecoderStarted(w, h); }
        });
    }

    private void postFormatChanged(final int w, final int h) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onFormatChanged(w, h); }
        });
    }

    private void postFps(final int fps, final long total) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onFpsUpdated(fps, total); }
        });
    }

    private void postError(final String error) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onDecoderError(error); }
        });
    }

    private void postStopped() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onDecoderStopped(); }
        });
    }
}
