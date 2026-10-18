package com.example.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.core.content.ContextCompat;

import com.example.log.SystemLogger;

import java.util.Collections;

/**
 * Manages HOST device camera preview overlay (front/back camera) using Camera2 API.
 * Supports Android 10+ (API 29+) with proper lifecycle and resource disposal.
 */
public class HostCameraManager {
    private static final String TAG = "CAMERA";

    public interface CameraStatusListener {
        void onCameraStarted(String cameraId, boolean isFrontFacing);
        void onCameraStopped();
        void onCameraError(String error);
    }

    private final Context mContext;
    private final CameraStatusListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    private TextureView mTextureView;
    private boolean mUseFrontCamera = true;
    private boolean mIsActive = false;

    public HostCameraManager(Context context, CameraStatusListener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
    }

    public synchronized void startCamera(TextureView textureView, boolean useFrontFacing) {
        stopCamera();

        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            SystemLogger.getInstance().log("CAMERA", "Camera permission is not granted on HOST");
            postError("Camera permission denied");
            return;
        }

        mTextureView = textureView;
        mUseFrontCamera = useFrontFacing;

        if (mTextureView.isAvailable()) {
            openCameraDevice();
        } else {
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCameraDevice();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    stopCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            });
        }
    }

    private void startBackgroundThread() {
        if (mCameraThread == null) {
            mCameraThread = new HandlerThread("MirrorWire-HostCamera");
            mCameraThread.start();
            mCameraHandler = new Handler(mCameraThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            try {
                mCameraThread.join(500);
            } catch (InterruptedException ignored) {}
            mCameraThread = null;
            mCameraHandler = null;
        }
    }

    private void openCameraDevice() {
        try {
            startBackgroundThread();
            String targetCameraId = findCameraId(mUseFrontCamera);
            if (targetCameraId == null) {
                targetCameraId = findCameraId(!mUseFrontCamera);
            }
            if (targetCameraId == null) {
                postError("No compatible camera found on HOST");
                return;
            }

            final String cameraId = targetCameraId;
            SystemLogger.getInstance().log(TAG, "Opening HOST camera ID=" + cameraId + " (front=" + mUseFrontCamera + ")...");

            if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        mCameraDevice = camera;
                        mIsActive = true;
                        createCameraPreviewSession();
                        postStarted(cameraId, mUseFrontCamera);
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        SystemLogger.getInstance().log(TAG, "HOST Camera disconnected");
                        stopCamera();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        String errMsg = "HOST Camera error code: " + error;
                        SystemLogger.getInstance().log("ERROR", errMsg);
                        stopCamera();
                        postError(errMsg);
                    }
                }, mCameraHandler);
            }
        } catch (CameraAccessException e) {
            String err = "CameraAccessException: " + e.getMessage();
            SystemLogger.getInstance().log("ERROR", err);
            postError(err);
        } catch (SecurityException e) {
            String err = "SecurityException accessing camera: " + e.getMessage();
            SystemLogger.getInstance().log("ERROR", err);
            postError(err);
        }
    }

    private void createCameraPreviewSession() {
        if (mCameraDevice == null || mTextureView == null || !mTextureView.isAvailable()) {
            return;
        }

        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            if (texture == null) {
                return;
            }

            // Set default buffer size
            texture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(texture);

            final CaptureRequest.Builder previewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // Auto-focus mode if supported
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            mCameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (mCameraDevice == null) {
                        return;
                    }
                    mCaptureSession = session;
                    try {
                        mCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, mCameraHandler);
                        SystemLogger.getInstance().log(TAG, "HOST Camera preview session active");
                    } catch (CameraAccessException e) {
                        SystemLogger.getInstance().log("ERROR", "Failed to start camera repeating preview: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    SystemLogger.getInstance().log("ERROR", "Camera capture session configuration failed");
                    postError("Camera preview configuration failed");
                }
            }, mCameraHandler);

        } catch (CameraAccessException e) {
            SystemLogger.getInstance().log("ERROR", "createCameraPreviewSession CameraAccessException: " + e.getMessage());
        }
    }

    private String findCameraId(boolean frontFacing) {
        try {
            int desiredFacing = frontFacing ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == desiredFacing) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            SystemLogger.getInstance().log("ERROR", "Error enumerating camera IDs: " + e.getMessage());
        }
        return null;
    }

    public synchronized void switchCamera() {
        if (!mIsActive) {
            return;
        }
        startCamera(mTextureView, !mUseFrontCamera);
    }

    public synchronized void stopCamera() {
        mIsActive = false;

        if (mCaptureSession != null) {
            try {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
            } catch (Exception ignored) {}
            mCaptureSession = null;
        }

        if (mCameraDevice != null) {
            try {
                mCameraDevice.close();
            } catch (Exception ignored) {}
            mCameraDevice = null;
        }

        stopBackgroundThread();
        postStopped();
    }

    public boolean isFrontFacing() {
        return mUseFrontCamera;
    }

    public boolean isActive() {
        return mIsActive;
    }

    private void postStarted(final String id, final boolean front) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onCameraStarted(id, front); }
        });
    }

    private void postStopped() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onCameraStopped(); }
        });
    }

    private void postError(final String err) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onCameraError(err); }
        });
    }
}
