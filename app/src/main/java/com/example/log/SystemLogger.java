package com.example.log;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe, bounded ring-buffer system logger for MirrorWire V2.
 * Keeps memory bounded (max 500 lines) and delivers real-time updates to UI.
 */
public class SystemLogger {
    private static final int MAX_LOG_ENTRIES = 500;
    private static SystemLogger sInstance;

    public interface LogUpdateListener {
        void onLogAdded(String formattedEntry);
        void onLogsCleared();
    }

    private final ArrayDeque<String> mLogBuffer = new ArrayDeque<>(MAX_LOG_ENTRIES);
    private final List<LogUpdateListener> mListeners = new ArrayList<>();
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private SystemLogger() {}

    public static synchronized SystemLogger getInstance() {
        if (sInstance == null) {
            sInstance = new SystemLogger();
        }
        return sInstance;
    }

    public synchronized void log(String tag, String message) {
        String timestamp = mDateFormat.format(new Date());
        final String entry = String.format(Locale.US, "[%s] [%s] %s", timestamp, tag, message);

        if (mLogBuffer.size() >= MAX_LOG_ENTRIES) {
            mLogBuffer.pollFirst();
        }
        mLogBuffer.addLast(entry);

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (SystemLogger.this) {
                    for (LogUpdateListener listener : mListeners) {
                        listener.onLogAdded(entry);
                    }
                }
            }
        });
    }

    public synchronized void addListener(LogUpdateListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public synchronized void removeListener(LogUpdateListener listener) {
        mListeners.remove(listener);
    }

    public synchronized List<String> getAllLogs() {
        return new ArrayList<>(mLogBuffer);
    }

    public synchronized String getAllLogsAsString() {
        StringBuilder sb = new StringBuilder();
        for (String entry : mLogBuffer) {
            sb.append(entry).append("\n");
        }
        return sb.toString();
    }

    public synchronized void clear() {
        mLogBuffer.clear();
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (SystemLogger.this) {
                    for (LogUpdateListener listener : mListeners) {
                        listener.onLogsCleared();
                    }
                }
            }
        });
    }

    public void copyToClipboard(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("MirrorWire_V2_Logs", getAllLogsAsString());
            clipboard.setPrimaryClip(clip);
        }
    }
}
