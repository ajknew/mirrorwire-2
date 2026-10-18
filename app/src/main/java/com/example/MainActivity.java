package com.example;

import android.Manifest;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.adb.AdbConnection;
import com.example.adb.AdbCrypto;
import com.example.audio.MirrorAudioManager;
import com.example.camera.HostCameraManager;
import com.example.config.CaptureSettings;
import com.example.config.LayoutPresetManager;
import com.example.log.SystemLogger;
import com.example.video.H264Parser;
import com.example.video.SpsParser;
import com.example.video.VideoDecoder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;

/**
 * Main Activity for MirrorWire V2 - USB OTG Screen Mirroring.
 * Implements real ADB communication, H.264 stream parsing, hardware MediaCodec decoding,
 * Host camera preview overlay, capture settings, layout presets, and diagnostic logging.
 */
public class MainActivity extends AppCompatActivity implements
        SurfaceHolder.Callback,
        AdbConnection.ConnectionListener,
        VideoDecoder.DecoderListener,
        HostCameraManager.CameraStatusListener,
        MirrorAudioManager.AudioListener,
        SystemLogger.LogUpdateListener {

    private static final String ACTION_USB_PERMISSION = "com.mirrorwire.v2.USB_PERMISSION";
    private static final int PERMISSION_REQ_CAMERA = 101;

    // UI Elements
    private TextView mTvStatusBadge;
    private TextView mChipFps;
    private TextView mChipResolution;
    private TextView mChipBitrate;
    private TextView mChipAudio;

    private FrameLayout mWorkspaceContainer;
    private SurfaceView mDisplaySurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private View mIdlePlaceholder;
    private TextView mTvLiveIndicator;

    private FrameLayout mCameraOverlayContainer;
    private TextureView mCameraTextureView;
    private Button mBtnCamSwitch;
    private Button mBtnCamLock;
    private Button mBtnCamClose;

    private Button mBtnStartMirror;
    private Button mBtnStopMirror;
    private Button mBtnLiveMirror;
    private Button mBtnCaptureSettings;
    private Button mBtnLayoutPreset;
    private Button mBtnAudioToggle;
    private Button mBtnCameraToggle;

    private View mHeaderContainer;
    private View mControlsContainer;
    private View mLogSectionContainer;

    private TextView mTvSystemLog;
    private ScrollView mScrollLog;
    private Button mBtnCopyLog;
    private Button mBtnClearLog;

    // Subsystems
    private UsbManager mUsbManager;
    private AdbCrypto mAdbCrypto;
    private AdbConnection mAdbConnection;
    private H264Parser mH264Parser;
    private VideoDecoder mVideoDecoder;
    private HostCameraManager mHostCameraManager;
    private MirrorAudioManager mMirrorAudioManager;
    private CaptureSettings mCaptureSettings;
    private LayoutPresetManager mLayoutPresetManager;

    private int mActiveVideoStreamId = -1;
    private boolean mIsFullscreenMode = false;
    private boolean mIsSurfaceCreated = false;

    // USB BroadcastReceiver
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            SystemLogger.getInstance().log("USB", "USB permission granted for device: " + device.getDeviceName());
                            mAdbConnection.connect(device);
                        }
                    } else {
                        SystemLogger.getInstance().log("USB", "USB permission denied by user");
                        updateStatusBadge("USB PERMISSION DENIED", "#EF4444");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    SystemLogger.getInstance().log("USB", "USB Device attached: VID=0x" +
                            Integer.toHexString(device.getVendorId()) + " PID=0x" + Integer.toHexString(device.getProductId()));
                    checkAndConnectUsbDevice();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    SystemLogger.getInstance().log("USB", "USB Device detached: " + device.getDeviceName());
                    stopMirroring("USB device detached");
                    mAdbConnection.disconnect("Device physically unplugged");
                    updateStatusBadge("USB DISCONNECTED", "#EF4444");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initSubsystems();
        setupListeners();
        registerUsbReceiver();

        // Check for already connected USB devices
        checkAndConnectUsbDevice();
    }

    private void bindViews() {
        mTvStatusBadge = findViewById(R.id.tv_status_badge);
        mChipFps = findViewById(R.id.chip_fps);
        mChipResolution = findViewById(R.id.chip_resolution);
        mChipBitrate = findViewById(R.id.chip_bitrate);
        mChipAudio = findViewById(R.id.chip_audio);

        mWorkspaceContainer = findViewById(R.id.workspace_container);
        mDisplaySurfaceView = findViewById(R.id.display_surface_view);
        mIdlePlaceholder = findViewById(R.id.idle_placeholder);
        mTvLiveIndicator = findViewById(R.id.tv_live_indicator);

        mCameraOverlayContainer = findViewById(R.id.camera_overlay_container);
        mCameraTextureView = findViewById(R.id.camera_texture_view);
        mBtnCamSwitch = findViewById(R.id.btn_cam_switch);
        mBtnCamLock = findViewById(R.id.btn_cam_lock);
        mBtnCamClose = findViewById(R.id.btn_cam_close);

        mBtnStartMirror = findViewById(R.id.btn_start_mirror);
        mBtnStopMirror = findViewById(R.id.btn_stop_mirror);
        mBtnLiveMirror = findViewById(R.id.btn_live_mirror);
        mBtnCaptureSettings = findViewById(R.id.btn_capture_settings);
        mBtnLayoutPreset = findViewById(R.id.btn_layout_preset);
        mBtnAudioToggle = findViewById(R.id.btn_audio_toggle);
        mBtnCameraToggle = findViewById(R.id.btn_camera_toggle);

        mHeaderContainer = findViewById(R.id.header_container);
        mControlsContainer = findViewById(R.id.controls_container);
        mLogSectionContainer = findViewById(R.id.log_section_container);

        mTvSystemLog = findViewById(R.id.tv_system_log);
        mScrollLog = findViewById(R.id.scroll_log);
        mBtnCopyLog = findViewById(R.id.btn_copy_log);
        mBtnClearLog = findViewById(R.id.btn_clear_log);

        mSurfaceHolder = mDisplaySurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
    }

    private void initSubsystems() {
        SystemLogger.getInstance().addListener(this);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mAdbCrypto = AdbCrypto.loadOrCreate(this);
        mAdbConnection = new AdbConnection(mUsbManager, mAdbCrypto, this);

        mCaptureSettings = new CaptureSettings(this);
        mLayoutPresetManager = new LayoutPresetManager(this);

        mVideoDecoder = new VideoDecoder(this);
        mHostCameraManager = new HostCameraManager(this, this);
        mMirrorAudioManager = new MirrorAudioManager(this);

        mH264Parser = new H264Parser(new H264Parser.FrameCallback() {
            @Override
            public void onSpsReceived(byte[] spsBytes, final SpsParser.SpsInfo info) {
                if (info != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mChipResolution.setText(String.format(Locale.US, "RES: %dx%d", info.width, info.height));
                        }
                    });
                    if (mIsSurfaceCreated && (!mVideoDecoder.isRunning() || mVideoDecoder.getWidth() != info.width || mVideoDecoder.getHeight() != info.height)) {
                        mVideoDecoder.initAndStart(info.width, info.height, spsBytes,
                                mH264Parser.getLastPps(), mSurfaceHolder.getSurface(), mCaptureSettings.getHostFps());
                    }
                }
            }

            @Override
            public void onPpsReceived(byte[] ppsBytes) {
                // PPS stored in parser
            }

            @Override
            public void onAccessUnitReady(byte[] accessUnitData, boolean isKeyFrame) {
                if (mVideoDecoder.isRunning()) {
                    mVideoDecoder.feedAccessUnit(accessUnitData, isKeyFrame);
                } else if (mIsSurfaceCreated) {
                    // Start decoder with fallback/current dimensions if not yet started
                    mVideoDecoder.initAndStart(mCaptureSettings.getWidth(), mCaptureSettings.getHeight(),
                            mH264Parser.getLastSps(), mH264Parser.getLastPps(),
                            mSurfaceHolder.getSurface(), mCaptureSettings.getHostFps());
                    mVideoDecoder.feedAccessUnit(accessUnitData, isKeyFrame);
                }
            }
        });

        // Initialize display chip labels
        mChipBitrate.setText(String.format(Locale.US, "BITRATE: %.1f Mbps", mCaptureSettings.getBitrateBps() / 1_000_000.0f));

        // Setup touch dragging for Camera overlay
        new TouchOverlayHelper(mCameraOverlayContainer, mWorkspaceContainer,
                mLayoutPresetManager.getCameraElement(),
                new TouchOverlayHelper.OnOverlayModifiedListener() {
                    @Override
                    public void onOverlayMovedOrResized(float xP, float yP, float wP, float hP) {
                        mLayoutPresetManager.save();
                    }
                });

        applyLayoutPresetState();
    }

    private void setupListeners() {
        mBtnStartMirror.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startMirroring();
            }
        });

        mBtnStopMirror.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopMirroring("User tapped STOP MIRROR");
            }
        });

        mBtnLiveMirror.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLiveFullscreen();
            }
        });

        mWorkspaceContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mIsFullscreenMode) {
                    // Tap on fullscreen returns controls
                    toggleLiveFullscreen();
                }
            }
        });

        mBtnCaptureSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCaptureSettingsDialog();
            }
        });

        mBtnLayoutPreset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLayoutPresetDialog();
            }
        });

        mBtnAudioToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mMirrorAudioManager.toggleAudio(mAdbConnection);
            }
        });

        mBtnCameraToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHostCamera();
            }
        });

        // Camera overlay buttons
        mBtnCamSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHostCameraManager.switchCamera();
            }
        });

        mBtnCamLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean locked = !mLayoutPresetManager.getCameraElement().isLocked;
                mLayoutPresetManager.getCameraElement().isLocked = locked;
                mBtnCamLock.setText(locked ? "🔒" : "🔓");
                mLayoutPresetManager.save();
                Toast.makeText(MainActivity.this, locked ? "Camera overlay locked" : "Camera overlay unlocked", Toast.LENGTH_SHORT).show();
            }
        });

        mBtnCamClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHostCameraManager.stopCamera();
                mCameraOverlayContainer.setVisibility(View.GONE);
                mLayoutPresetManager.getCameraElement().isVisible = false;
                mLayoutPresetManager.save();
            }
        });

        mBtnCopyLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemLogger.getInstance().copyToClipboard(MainActivity.this);
                Toast.makeText(MainActivity.this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        mBtnClearLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemLogger.getInstance().clear();
            }
        });
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);
    }

    private void checkAndConnectUsbDevice() {
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            SystemLogger.getInstance().log("USB", "No USB OTG devices currently detected on HOST");
            updateStatusBadge("USB DISCONNECTED", "#EF4444");
            return;
        }

        SystemLogger.getInstance().log("USB", "Found " + deviceList.size() + " connected USB device(s)");
        for (UsbDevice device : deviceList.values()) {
            updateStatusBadge("USB DETECTED", "#EAB308");
            if (mUsbManager.hasPermission(device)) {
                mAdbConnection.connect(device);
                return;
            } else {
                requestUsbPermission(device);
                return;
            }
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        SystemLogger.getInstance().log("USB", "Requesting system USB permission for VID=0x" +
                Integer.toHexString(device.getVendorId()));
        updateStatusBadge("AWAITING PERMISSION", "#F59E0B");

        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        mUsbManager.requestPermission(device, pi);
    }

    private synchronized void startMirroring() {
        if (!mAdbConnection.isConnectedAndReady()) {
            SystemLogger.getInstance().log("STREAM", "Connecting USB/ADB before starting mirror...");
            checkAndConnectUsbDevice();
            if (!mAdbConnection.isConnectedAndReady()) {
                Toast.makeText(this, "Connect USB OTG cable & ensure USB debugging is enabled on CLIENT", Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (mActiveVideoStreamId != -1) {
            SystemLogger.getInstance().log("STREAM", "Stream already running with channel ID " + mActiveVideoStreamId);
            return;
        }

        updateStatusBadge("STARTING STREAM", "#3B82F6");
        mH264Parser.reset();

        String command = mCaptureSettings.buildScreenrecordCommand();
        SystemLogger.getInstance().log("STREAM", "Issuing screenrecord shell command to CLIENT: " + command);

        try {
            mActiveVideoStreamId = mAdbConnection.openStream(command, new AdbConnection.StreamListener() {
                @Override
                public void onStreamReady(int localId, int remoteId) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatusBadge("STREAM ACTIVE", "#10B981");
                            mBtnStartMirror.setEnabled(false);
                            mBtnStopMirror.setEnabled(true);
                            mIdlePlaceholder.setVisibility(View.GONE);
                            mTvLiveIndicator.setVisibility(View.VISIBLE);
                        }
                    });
                }

                @Override
                public void onDataReceived(byte[] data) {
                    mH264Parser.feedData(data, 0, data.length);
                }

                @Override
                public void onStreamClosed(int localId) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stopMirroring("Stream channel closed");
                        }
                    });
                }
            });
        } catch (IOException e) {
            String err = "Failed to launch screenrecord: " + e.getMessage();
            SystemLogger.getInstance().log("ERROR", err);
            updateStatusBadge("STREAM ERROR", "#EF4444");
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        }
    }

    private synchronized void stopMirroring(String reason) {
        SystemLogger.getInstance().log("STREAM", "Stopping screen mirroring: " + reason);

        if (mActiveVideoStreamId != -1) {
            mAdbConnection.closeStream(mActiveVideoStreamId);
            mActiveVideoStreamId = -1;
        }

        mVideoDecoder.stop();
        mH264Parser.reset();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBtnStartMirror.setEnabled(true);
                mBtnStopMirror.setEnabled(false);
                mTvLiveIndicator.setVisibility(View.GONE);
                mIdlePlaceholder.setVisibility(View.VISIBLE);
                mChipFps.setText("FPS: 0");
                updateStatusBadge(mAdbConnection.isConnectedAndReady() ? "ADB READY" : "STOPPED",
                        mAdbConnection.isConnectedAndReady() ? "#10B981" : "#64748B");
            }
        });
    }

    private void toggleLiveFullscreen() {
        mIsFullscreenMode = !mIsFullscreenMode;

        int visibility = mIsFullscreenMode ? View.GONE : View.VISIBLE;
        mHeaderContainer.setVisibility(visibility);
        mControlsContainer.setVisibility(visibility);
        mLogSectionContainer.setVisibility(visibility);

        if (mIsFullscreenMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            Toast.makeText(this, "Fullscreen Live View (Tap screen to restore controls)", Toast.LENGTH_SHORT).show();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void toggleHostCamera() {
        if (mHostCameraManager.isActive()) {
            mHostCameraManager.stopCamera();
            mCameraOverlayContainer.setVisibility(View.GONE);
            mLayoutPresetManager.getCameraElement().isVisible = false;
            mLayoutPresetManager.save();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQ_CAMERA);
            } else {
                mCameraOverlayContainer.setVisibility(View.VISIBLE);
                mLayoutPresetManager.getCameraElement().isVisible = true;
                mLayoutPresetManager.save();
                mHostCameraManager.startCamera(mCameraTextureView, true);
            }
        }
    }

    private void applyLayoutPresetState() {
        LayoutPresetManager.LayoutElement camElem = mLayoutPresetManager.getCameraElement();
        if (camElem.isVisible) {
            mCameraOverlayContainer.setVisibility(View.VISIBLE);
            mBtnCamLock.setText(camElem.isLocked ? "🔒" : "🔓");
        } else {
            mCameraOverlayContainer.setVisibility(View.GONE);
        }
    }

    private void showCaptureSettingsDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_capture_settings);

        Spinner spRes = dialog.findViewById(R.id.spinner_resolution);
        Spinner spBitrate = dialog.findViewById(R.id.spinner_bitrate);
        Spinner spHostFps = dialog.findViewById(R.id.spinner_host_fps);
        final TextView tvBitrateInfo = dialog.findViewById(R.id.tv_bitrate_conversion_info);
        CheckBox cbLoop = dialog.findViewById(R.id.cb_continuous_loop);

        Button btnSave = dialog.findViewById(R.id.btn_save_settings);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel_settings);

        // Populate Resolution Spinner
        ArrayAdapter<CaptureSettings.ResolutionOption> resAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, CaptureSettings.RESOLUTION_OPTIONS);
        spRes.setAdapter(resAdapter);
        int selectedResIdx = 2; // Default 1280x720
        for (int i = 0; i < CaptureSettings.RESOLUTION_OPTIONS.length; i++) {
            if (CaptureSettings.RESOLUTION_OPTIONS[i].width == mCaptureSettings.getWidth() &&
                    CaptureSettings.RESOLUTION_OPTIONS[i].height == mCaptureSettings.getHeight()) {
                selectedResIdx = i;
                break;
            }
        }
        spRes.setSelection(selectedResIdx);

        // Populate Bitrate Spinner
        ArrayAdapter<CaptureSettings.BitrateOption> bitAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, CaptureSettings.BITRATE_OPTIONS);
        spBitrate.setAdapter(bitAdapter);
        int selectedBitIdx = 8; // Default 3 Mbps
        for (int i = 0; i < CaptureSettings.BITRATE_OPTIONS.length; i++) {
            if (CaptureSettings.BITRATE_OPTIONS[i].bps == mCaptureSettings.getBitrateBps()) {
                selectedBitIdx = i;
                break;
            }
        }
        spBitrate.setSelection(selectedBitIdx);

        spBitrate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                CaptureSettings.BitrateOption opt = CaptureSettings.BITRATE_OPTIONS[position];
                tvBitrateInfo.setText(String.format(Locale.US, "Integer Bitrate: %,d bps", opt.bps));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Populate HOST FPS Spinner
        Integer[] fpsList = new Integer[]{15, 24, 30, 45, 60};
        ArrayAdapter<Integer> fpsAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, fpsList);
        spHostFps.setAdapter(fpsAdapter);
        int selectedFpsIdx = 4; // 60 FPS
        for (int i = 0; i < fpsList.length; i++) {
            if (fpsList[i] == mCaptureSettings.getHostFps()) {
                selectedFpsIdx = i;
                break;
            }
        }
        spHostFps.setSelection(selectedFpsIdx);

        cbLoop.setChecked(mCaptureSettings.isEnableContinuousLoop());

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CaptureSettings.ResolutionOption res = (CaptureSettings.ResolutionOption) spRes.getSelectedItem();
                CaptureSettings.BitrateOption bit = (CaptureSettings.BitrateOption) spBitrate.getSelectedItem();
                Integer fps = (Integer) spHostFps.getSelectedItem();

                mCaptureSettings.setWidth(res.width);
                mCaptureSettings.setHeight(res.height);
                mCaptureSettings.setBitrateBps(bit.bps);
                mCaptureSettings.setHostFps(fps);
                mCaptureSettings.setEnableContinuousLoop(cbLoop.isChecked());
                mCaptureSettings.save();

                mChipBitrate.setText(String.format(Locale.US, "BITRATE: %.1f Mbps", bit.bps / 1_000_000.0f));
                SystemLogger.getInstance().log("CONFIG", String.format("Capture settings saved: %dx%d, %d bps, HOST FPS target: %d",
                        res.width, res.height, bit.bps, fps));

                dialog.dismiss();
                Toast.makeText(MainActivity.this, "Settings saved. Will apply to next/current mirror stream.", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private void showLayoutPresetDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_preset_layout);

        Button btnPip = dialog.findViewById(R.id.btn_preset_pip);
        Button btnSplit = dialog.findViewById(R.id.btn_preset_split);
        Button btnDisplayOnly = dialog.findViewById(R.id.btn_preset_display_only);

        CheckBox cbDisplayVis = dialog.findViewById(R.id.cb_display_visible);
        CheckBox cbDisplayLock = dialog.findViewById(R.id.cb_display_lock);
        CheckBox cbCamVis = dialog.findViewById(R.id.cb_camera_visible);
        CheckBox cbCamLock = dialog.findViewById(R.id.cb_camera_lock);
        Button btnToggleZ = dialog.findViewById(R.id.btn_toggle_layer_z);
        Button btnClose = dialog.findViewById(R.id.btn_close_layout);

        cbDisplayVis.setChecked(mLayoutPresetManager.getDisplayElement().isVisible);
        cbDisplayLock.setChecked(mLayoutPresetManager.getDisplayElement().isLocked);
        cbCamVis.setChecked(mLayoutPresetManager.getCameraElement().isVisible);
        cbCamLock.setChecked(mLayoutPresetManager.getCameraElement().isLocked);

        btnPip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLayoutPresetManager.applyPreset(LayoutPresetManager.PresetType.DEFAULT_PIP);
                applyLayoutPresetState();
                dialog.dismiss();
            }
        });

        btnSplit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLayoutPresetManager.applyPreset(LayoutPresetManager.PresetType.SPLIT_SCREEN);
                applyLayoutPresetState();
                dialog.dismiss();
            }
        });

        btnDisplayOnly.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLayoutPresetManager.applyPreset(LayoutPresetManager.PresetType.DISPLAY_ONLY);
                applyLayoutPresetState();
                dialog.dismiss();
            }
        });

        cbCamVis.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mLayoutPresetManager.getCameraElement().isVisible = isChecked;
                mLayoutPresetManager.save();
                applyLayoutPresetState();
            }
        });

        cbCamLock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mLayoutPresetManager.getCameraElement().isLocked = isChecked;
                mLayoutPresetManager.save();
                applyLayoutPresetState();
            }
        });

        btnToggleZ.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int camZ = mLayoutPresetManager.getCameraElement().zOrder;
                int dispZ = mLayoutPresetManager.getDisplayElement().zOrder;
                mLayoutPresetManager.getCameraElement().zOrder = dispZ;
                mLayoutPresetManager.getDisplayElement().zOrder = camZ;
                mLayoutPresetManager.save();
                if (mLayoutPresetManager.getCameraElement().zOrder > mLayoutPresetManager.getDisplayElement().zOrder) {
                    mCameraOverlayContainer.bringToFront();
                } else {
                    mDisplaySurfaceView.bringToFront();
                }
                Toast.makeText(MainActivity.this, "Layer Z-Order updated", Toast.LENGTH_SHORT).show();
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private void updateStatusBadge(final String text, final String colorHex) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTvStatusBadge.setText(text);
                try {
                    mTvStatusBadge.setTextColor(android.graphics.Color.parseColor(colorHex));
                } catch (Exception ignored) {}
            }
        });
    }

    // --- SurfaceHolder.Callback ---
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        SystemLogger.getInstance().log("DECODER", "Display Surface created and valid");
        mIsSurfaceCreated = true;
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        SystemLogger.getInstance().log("DECODER", String.format(Locale.US, "Display Surface size changed: %dx%d", width, height));
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        SystemLogger.getInstance().log("DECODER", "Display Surface destroyed");
        mIsSurfaceCreated = false;
        mVideoDecoder.stop();
    }

    // --- AdbConnection.ConnectionListener ---
    @Override
    public void onDeviceDetected(UsbDevice device) {
        updateStatusBadge("USB DETECTED", "#EAB308");
    }

    @Override
    public void onUsbPermissionRequired(UsbDevice device) {
        requestUsbPermission(device);
    }

    @Override
    public void onAdbConnecting() {
        updateStatusBadge("ADB CONNECTING...", "#38BDF8");
    }

    @Override
    public void onAdbAuthRequired() {
        updateStatusBadge("AUTHORIZE ON CLIENT", "#F59E0B");
        Toast.makeText(this, "Please accept 'Allow USB debugging' prompt on CLIENT phone screen", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onAdbConnected(String banner) {
        updateStatusBadge("ADB READY", "#10B981");
        Toast.makeText(this, "Connected to CLIENT via ADB: " + banner.trim(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAdbDisconnected(String reason) {
        updateStatusBadge("DISCONNECTED", "#64748B");
    }

    @Override
    public void onError(String error) {
        updateStatusBadge("ERROR", "#EF4444");
    }

    // --- VideoDecoder.DecoderListener ---
    @Override
    public void onDecoderStarted(int width, int height) {
        updateStatusBadge("DECODER RUNNING", "#10B981");
    }

    @Override
    public void onFormatChanged(int newWidth, int newHeight) {
        mChipResolution.setText(String.format(Locale.US, "RES: %dx%d", newWidth, newHeight));
    }

    @Override
    public void onFpsUpdated(int fps, long totalFramesSubmitted) {
        mChipFps.setText(String.format(Locale.US, "FPS: %d", fps));
        if (fps > 0) {
            updateStatusBadge("DISPLAYING FRAMES", "#22C55E");
        }
    }

    @Override
    public void onDecoderError(String error) {
        updateStatusBadge("DECODER ERROR", "#EF4444");
    }

    @Override
    public void onDecoderStopped() {
        mChipFps.setText("FPS: 0");
    }

    // --- HostCameraManager.CameraStatusListener ---
    @Override
    public void onCameraStarted(String cameraId, boolean isFrontFacing) {
        mBtnCameraToggle.setText("CAM ON");
        SystemLogger.getInstance().log("CAMERA", "HOST Camera active (" + (isFrontFacing ? "Front" : "Back") + ")");
    }

    @Override
    public void onCameraStopped() {
        mBtnCameraToggle.setText("CAMERA");
    }

    @Override
    public void onCameraError(String error) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
    }

    // --- MirrorAudioManager.AudioListener ---
    @Override
    public void onAudioStateChanged(MirrorAudioManager.AudioState state, String detail) {
        mChipAudio.setText("AUDIO: " + state.label);
        mBtnAudioToggle.setText(state == MirrorAudioManager.AudioState.ACTIVE ? "AUDIO ON" : "AUDIO");
        SystemLogger.getInstance().log("AUDIO", "State: " + state.label + " (" + detail + ")");
    }

    // --- SystemLogger.LogUpdateListener ---
    @Override
    public void onLogAdded(String formattedEntry) {
        mTvSystemLog.append(formattedEntry + "\n");
        mScrollLog.post(new Runnable() {
            @Override
            public void run() {
                mScrollLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    public void onLogsCleared() {
        mTvSystemLog.setText("");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mCameraOverlayContainer.setVisibility(View.VISIBLE);
                mLayoutPresetManager.getCameraElement().isVisible = true;
                mLayoutPresetManager.save();
                mHostCameraManager.startCamera(mCameraTextureView, true);
            } else {
                Toast.makeText(this, "Camera permission denied by user", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mUsbReceiver);
        } catch (Exception ignored) {}
        SystemLogger.getInstance().removeListener(this);
        stopMirroring("Activity destroyed");
        mHostCameraManager.stopCamera();
        mMirrorAudioManager.stopAudio(mAdbConnection);
        mAdbConnection.disconnect("Activity destroyed");
    }
}
