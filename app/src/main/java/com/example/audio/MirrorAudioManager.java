package com.example.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.example.adb.AdbConnection;
import com.example.log.SystemLogger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages audio forwarding session, codec headers, and AudioTrack playback.
 * Does not block or interfere with the primary USB-IN video reader thread.
 * Provides completely honest status and diagnostic logging regarding CLIENT capabilities.
 */
public class MirrorAudioManager {
    private static final String TAG = "AUDIO";

    public enum AudioState {
        OFF("Audio Off"),
        CONNECTING("Connecting Audio..."),
        ACTIVE("Audio Streaming"),
        UNSUPPORTED("Audio Companion Required"),
        ERROR("Audio Error");

        public final String label;
        AudioState(String label) { this.label = label; }
    }

    public interface AudioListener {
        void onAudioStateChanged(AudioState state, String detail);
    }

    private final AudioListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private AudioState mCurrentState = AudioState.OFF;
    private final AtomicBoolean mIsAudioEnabled = new AtomicBoolean(false);
    private AudioTrack mAudioTrack;
    private int mAudioChannelId = -1;

    public MirrorAudioManager(AudioListener listener) {
        mListener = listener;
    }

    public synchronized void toggleAudio(final AdbConnection adbConnection) {
        if (mIsAudioEnabled.get()) {
            stopAudio(adbConnection);
        } else {
            startAudio(adbConnection);
        }
    }

    public synchronized void startAudio(final AdbConnection adbConnection) {
        mIsAudioEnabled.set(true);
        setState(AudioState.CONNECTING, "Checking audio forwarding capability...");

        if (adbConnection == null || !adbConnection.isConnectedAndReady()) {
            SystemLogger.getInstance().log(TAG, "Cannot start audio: ADB is not connected");
            setState(AudioState.OFF, "ADB not connected");
            mIsAudioEnabled.set(false);
            return;
        }

        SystemLogger.getInstance().log(TAG, "Standard screenrecord CLI captures video only. Probing audio forward channel...");

        // Try opening audio companion stream channel
        try {
            mAudioChannelId = adbConnection.openStream("tcp:28200", new AdbConnection.StreamListener() {
                @Override
                public void onStreamReady(int localId, int remoteId) {
                    SystemLogger.getInstance().log(TAG, "Audio stream channel established on tcp:28200");
                    initAudioTrack();
                    setState(AudioState.ACTIVE, "Audio streaming via tcp:28200");
                }

                @Override
                public void onDataReceived(byte[] data) {
                    playAudioChunk(data);
                }

                @Override
                public void onStreamClosed(int localId) {
                    SystemLogger.getInstance().log(TAG, "Audio stream channel closed");
                    stopAudioTrack();
                    setState(AudioState.OFF, "Audio channel closed");
                }
            });
        } catch (IOException e) {
            String explanation = "Standard screenrecord does not forward internal audio. " +
                    "Audio requires client-side companion forwarder (sndcpy) or Android 10+ AudioPlaybackCapture. " +
                    "Video streaming is unaffected.";
            SystemLogger.getInstance().log(TAG, explanation);
            setState(AudioState.UNSUPPORTED, "Requires companion forwarder / Android 10+ AudioCapture");
        }
    }

    private void initAudioTrack() {
        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBufferSize, 32 * 1024);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mAudioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                        audioFormat, bufferSize, AudioTrack.MODE_STREAM);
            }
            mAudioTrack.play();
            SystemLogger.getInstance().log(TAG, "AudioTrack initialized (48kHz Stereo 16-bit PCM)");
        } catch (Exception e) {
            SystemLogger.getInstance().log("ERROR", "Failed to initialize AudioTrack: " + e.getMessage());
            setState(AudioState.ERROR, "AudioTrack initialization error: " + e.getMessage());
        }
    }

    private void playAudioChunk(byte[] data) {
        if (mAudioTrack != null && mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            mAudioTrack.write(data, 0, data.length);
        }
    }

    public synchronized void stopAudio(AdbConnection adbConnection) {
        mIsAudioEnabled.set(false);
        if (mAudioChannelId != -1 && adbConnection != null) {
            adbConnection.closeStream(mAudioChannelId);
            mAudioChannelId = -1;
        }
        stopAudioTrack();
        setState(AudioState.OFF, "Audio disabled");
    }

    private void stopAudioTrack() {
        if (mAudioTrack != null) {
            try {
                mAudioTrack.stop();
                mAudioTrack.release();
            } catch (Exception ignored) {}
            mAudioTrack = null;
        }
    }

    public boolean isAudioEnabled() {
        return mIsAudioEnabled.get();
    }

    public AudioState getState() {
        return mCurrentState;
    }

    private void setState(final AudioState state, final String detail) {
        mCurrentState = state;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onAudioStateChanged(state, detail);
            }
        });
    }
}
