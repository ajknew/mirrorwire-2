package com.example.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

import com.example.log.SystemLogger;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages USB transport, single-owner USB-IN reader loop, ADB protocol state machine,
 * RSA authentication, and multiplexed stream channels.
 */
public class AdbConnection {
    private static final String TAG = "ADB";

    public interface ConnectionListener {
        void onDeviceDetected(UsbDevice device);
        void onUsbPermissionRequired(UsbDevice device);
        void onAdbConnecting();
        void onAdbAuthRequired();
        void onAdbConnected(String banner);
        void onAdbDisconnected(String reason);
        void onError(String error);
    }

    public interface StreamListener {
        void onStreamReady(int localId, int remoteId);
        void onDataReceived(byte[] data);
        void onStreamClosed(int localId);
    }

    public static class StreamChannel {
        public final int localId;
        public volatile int remoteId = 0;
        public final String destination;
        public final StreamListener listener;
        public volatile boolean isOpen = false;

        public StreamChannel(int localId, String destination, StreamListener listener) {
            this.localId = localId;
            this.destination = destination;
            this.listener = listener;
        }
    }

    private final UsbManager mUsbManager;
    private final AdbCrypto mAdbCrypto;
    private final ConnectionListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private UsbDevice mUsbDevice;
    private UsbDeviceConnection mUsbConnection;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mEndpointIn;
    private UsbEndpoint mEndpointOut;

    private final Object mWriteLock = new Object();
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final AtomicBoolean mIsAdbReady = new AtomicBoolean(false);
    private Thread mReaderThread;

    private final AtomicInteger mNextChannelId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, StreamChannel> mActiveChannels = new ConcurrentHashMap<>();

    private int mMaxPayload = 256 * 1024;
    private boolean mAuthSignatureSent = false;

    public AdbConnection(UsbManager usbManager, AdbCrypto adbCrypto, ConnectionListener listener) {
        mUsbManager = usbManager;
        mAdbCrypto = adbCrypto;
        mListener = listener;
    }

    /**
     * Checks if a UsbInterface is an ADB interface (Class 0xFF, Subclass 0x42, Protocol 0x01).
     */
    public static boolean isAdbInterface(UsbInterface intf) {
        return (intf.getInterfaceClass() == 0xFF &&
                intf.getInterfaceSubclass() == 0x42 &&
                intf.getInterfaceProtocol() == 0x01);
    }

    /**
     * Finds the ADB interface or suitable bulk IN/OUT endpoints in any detected device.
     */
    public static UsbInterface findAdbInterface(UsbDevice device) {
        // Priority 1: Check standard ADB class/subclass/protocol
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (isAdbInterface(intf)) {
                return intf;
            }
        }
        // Priority 2: Fallback to interface having both BULK IN and BULK OUT endpoints
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            boolean hasBulkIn = false;
            boolean hasBulkOut = false;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) hasBulkIn = true;
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) hasBulkOut = true;
                }
            }
            if (hasBulkIn && hasBulkOut) {
                return intf;
            }
        }
        return null;
    }

    /**
     * Connects to the given USB device and initiates the ADB handshake.
     */
    public synchronized boolean connect(UsbDevice device) {
        disconnect("Initiating new connection");

        mUsbDevice = device;
        if (mUsbDevice == null) {
            postError("No USB device specified");
            return false;
        }

        SystemLogger.getInstance().log("USB", String.format("Inspecting device: VID=0x%04X, PID=0x%04X, Interfaces=%d",
                device.getVendorId(), device.getProductId(), device.getInterfaceCount()));

        if (!mUsbManager.hasPermission(device)) {
            SystemLogger.getInstance().log("USB", "USB permission not granted. Requesting user permission...");
            postUsbPermissionRequired(device);
            return false;
        }

        mUsbInterface = findAdbInterface(device);
        if (mUsbInterface == null) {
            String err = "Device does not provide an ADB interface (subclass 0x42, protocol 0x01) or bulk endpoints";
            SystemLogger.getInstance().log("USB", err);
            postError(err);
            return false;
        }

        SystemLogger.getInstance().log("USB", String.format("Found target interface ID=%d, Endpoints=%d",
                mUsbInterface.getId(), mUsbInterface.getEndpointCount()));

        for (int i = 0; i < mUsbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = mUsbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    mEndpointIn = ep;
                } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    mEndpointOut = ep;
                }
            }
        }

        if (mEndpointIn == null || mEndpointOut == null) {
            String err = "Missing bulk IN or OUT endpoint on ADB interface";
            SystemLogger.getInstance().log("USB", err);
            postError(err);
            return false;
        }

        mUsbConnection = mUsbManager.openDevice(device);
        if (mUsbConnection == null) {
            String err = "Failed to open USB device connection";
            SystemLogger.getInstance().log("USB", err);
            postError(err);
            return false;
        }

        if (!mUsbConnection.claimInterface(mUsbInterface, true)) {
            String err = "Failed to claim USB interface";
            SystemLogger.getInstance().log("USB", err);
            mUsbConnection.close();
            mUsbConnection = null;
            postError(err);
            return false;
        }

        SystemLogger.getInstance().log("USB", "USB interface claimed. Bulk IN maxPacket=" +
                mEndpointIn.getMaxPacketSize() + ", Bulk OUT maxPacket=" + mEndpointOut.getMaxPacketSize());

        mIsRunning.set(true);
        mIsAdbReady.set(false);
        mAuthSignatureSent = false;

        // Start dedicated single USB-IN reader thread
        mReaderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                usbReaderLoop();
            }
        }, "MirrorWire-UsbReader");
        mReaderThread.start();

        // Send CNXN message to begin ADB handshake
        postAdbConnecting();
        sendCnxn();
        return true;
    }

    private void sendCnxn() {
        try {
            SystemLogger.getInstance().log(TAG, "Sending CNXN packet (VERSION=1, payload=256KB)...");
            AdbMessage cnxn = AdbMessage.createCnxn(AdbMessage.MAX_PAYLOAD, "host::features=cmd,shell_v2,stat_v2");
            sendMessage(cnxn);
        } catch (IOException e) {
            SystemLogger.getInstance().log("ERROR", "Failed to send CNXN: " + e.getMessage());
            disconnect("CNXN transmission failure");
        }
    }

    /**
     * Dedicated single owner of the USB-IN endpoint.
     * Continuously reads packets and dispatches them without thread contention.
     */
    private void usbReaderLoop() {
        byte[] headerBuffer = new byte[AdbMessage.HEADER_LENGTH];
        SystemLogger.getInstance().log("USB", "USB reader thread started");

        while (mIsRunning.get() && mUsbConnection != null) {
            // 1. Read exactly 24-byte header
            int headerBytesRead = readExact(mEndpointIn, headerBuffer, AdbMessage.HEADER_LENGTH, 5000);
            if (headerBytesRead < 0) {
                if (mIsRunning.get()) {
                    SystemLogger.getInstance().log("USB", "USB read timeout or disconnect");
                }
                break;
            }
            if (headerBytesRead != AdbMessage.HEADER_LENGTH) {
                SystemLogger.getInstance().log("USB", "Incomplete header read: " + headerBytesRead + " bytes");
                continue;
            }

            AdbMessage msg = AdbMessage.parseHeader(headerBuffer);
            if (msg == null || !msg.validate()) {
                SystemLogger.getInstance().log("ERROR", "Invalid message header received, command=" +
                        (msg != null ? msg.getCommandName() : "null"));
                continue;
            }

            // 2. Read payload if dataLength > 0
            int dataLength = msg.getDataLength();
            if (dataLength > 0) {
                byte[] dataBuffer = new byte[dataLength];
                int dataBytesRead = readExact(mEndpointIn, dataBuffer, dataLength, 10000);
                if (dataBytesRead != dataLength) {
                    SystemLogger.getInstance().log("ERROR", "Payload size mismatch. Expected " + dataLength + " got " + dataBytesRead);
                    continue;
                }
                msg.setData(dataBuffer);

                // Check payload checksum
                if (msg.getDataCheck() != AdbMessage.calculateChecksum(dataBuffer)) {
                    SystemLogger.getInstance().log("ERROR", "Checksum verification failed for " + msg.getCommandName());
                    continue;
                }
            }

            // 3. Dispatch validated message to protocol state handler
            handleIncomingAdbMessage(msg);
        }

        SystemLogger.getInstance().log("USB", "USB reader thread terminated cleanly");
        if (mIsRunning.get()) {
            disconnect("USB reader loop ended");
        }
    }

    /**
     * Helper to reliably read exact number of bytes from bulk IN endpoint,
     * handling multi-packet USB segmentation.
     */
    private int readExact(UsbEndpoint endpoint, byte[] buffer, int length, int timeoutMs) {
        int totalRead = 0;
        long startTime = System.currentTimeMillis();

        while (totalRead < length && mIsRunning.get()) {
            int remaining = length - totalRead;
            int read = mUsbConnection.bulkTransfer(endpoint, buffer, totalRead, remaining, timeoutMs);
            if (read < 0) {
                return (totalRead > 0) ? totalRead : -1;
            }
            totalRead += read;
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                break;
            }
        }
        return totalRead;
    }

    /**
     * Processes incoming ADB messages based on protocol specification.
     */
    private void handleIncomingAdbMessage(AdbMessage msg) {
        switch (msg.getCommand()) {
            case AdbMessage.A_CNXN:
                handleCnxn(msg);
                break;
            case AdbMessage.A_AUTH:
                handleAuth(msg);
                break;
            case AdbMessage.A_OKAY:
                handleOkay(msg);
                break;
            case AdbMessage.A_WRTE:
                handleWrte(msg);
                break;
            case AdbMessage.A_CLSE:
                handleClse(msg);
                break;
            default:
                SystemLogger.getInstance().log(TAG, "Unhandled command: " + msg.getCommandName());
                break;
        }
    }

    private void handleCnxn(AdbMessage msg) {
        mMaxPayload = Math.min(msg.getArg1(), AdbMessage.MAX_PAYLOAD);
        String banner = (msg.getData() != null) ? new String(msg.getData()) : "Unknown Device";
        mIsAdbReady.set(true);
        SystemLogger.getInstance().log(TAG, "ADB Connection Established! Device banner: " + banner.trim());
        postAdbConnected(banner);
    }

    private void handleAuth(AdbMessage msg) {
        int authType = msg.getArg0();
        SystemLogger.getInstance().log(TAG, "Received AUTH challenge type=" + authType);

        if (authType == AdbMessage.AUTH_TYPE_TOKEN) {
            byte[] token = msg.getData();
            if (!mAuthSignatureSent) {
                // First try signing with private key
                try {
                    SystemLogger.getInstance().log(TAG, "Signing token with RSA private key...");
                    byte[] signature = mAdbCrypto.signAdbToken(token);
                    mAuthSignatureSent = true;
                    AdbMessage reply = AdbMessage.createAuth(AdbMessage.AUTH_TYPE_SIGNATURE, signature);
                    sendMessage(reply);
                    return;
                } catch (Exception e) {
                    SystemLogger.getInstance().log("ERROR", "Token signing failed: " + e.getMessage());
                }
            }

            // If signature was rejected or not recognized, send RSAPUBLICKEY
            try {
                SystemLogger.getInstance().log(TAG, "Sending ADB RSAPublicKey blob. Prompting user on CLIENT device...");
                postAdbAuthRequired();
                byte[] pubKeyBlob = mAdbCrypto.getAdbPublicKeyBlob();
                AdbMessage pubKeyMsg = AdbMessage.createAuth(AdbMessage.AUTH_TYPE_RSAPUBLICKEY, pubKeyBlob);
                sendMessage(pubKeyMsg);
            } catch (Exception e) {
                SystemLogger.getInstance().log("ERROR", "Failed to send public key: " + e.getMessage());
                disconnect("Auth public key failure");
            }
        }
    }

    private void handleOkay(AdbMessage msg) {
        int localId = msg.getArg0();
        int remoteId = msg.getArg1();
        StreamChannel channel = mActiveChannels.get(localId);
        if (channel != null) {
            channel.remoteId = remoteId;
            channel.isOpen = true;
            SystemLogger.getInstance().log("STREAM", String.format("Stream OPENED (localId=%d, remoteId=%d) for: %s",
                    localId, remoteId, channel.destination));
            if (channel.listener != null) {
                channel.listener.onStreamReady(localId, remoteId);
            }
        }
    }

    private void handleWrte(AdbMessage msg) {
        int remoteId = msg.getArg0();
        int localId = msg.getArg1();
        StreamChannel channel = mActiveChannels.get(localId);

        if (channel != null && channel.isOpen) {
            byte[] payload = msg.getData();
            if (payload != null && payload.length > 0 && channel.listener != null) {
                channel.listener.onDataReceived(payload);
            }
            // CRITICAL ADB FLOW CONTROL: Acknowledge WRTE immediately with OKAY
            try {
                AdbMessage ack = AdbMessage.createOkay(localId, remoteId);
                sendMessage(ack);
            } catch (IOException e) {
                SystemLogger.getInstance().log("ERROR", "Failed to send OKAY flow control: " + e.getMessage());
            }
        }
    }

    private void handleClse(AdbMessage msg) {
        int localId = msg.getArg1();
        StreamChannel channel = mActiveChannels.remove(localId);
        if (channel != null) {
            channel.isOpen = false;
            SystemLogger.getInstance().log("STREAM", "Stream CLOSED (localId=" + localId + ")");
            if (channel.listener != null) {
                channel.listener.onStreamClosed(localId);
            }
        }
    }

    /**
     * Sends an ADB message synchronously through the bulk OUT endpoint.
     */
    public void sendMessage(AdbMessage message) throws IOException {
        synchronized (mWriteLock) {
            if (mUsbConnection == null || mEndpointOut == null) {
                throw new IOException("USB connection is closed");
            }

            // Write 24-byte header
            byte[] header = message.serializeHeader();
            int written = mUsbConnection.bulkTransfer(mEndpointOut, header, header.length, 5000);
            if (written != header.length) {
                throw new IOException("Failed to send ADB header. Expected " + header.length + " wrote " + written);
            }

            // Write payload if present
            byte[] data = message.getData();
            if (data != null && data.length > 0) {
                int dataWritten = mUsbConnection.bulkTransfer(mEndpointOut, data, data.length, 10000);
                if (dataWritten != data.length) {
                    throw new IOException("Failed to send ADB payload. Expected " + data.length + " wrote " + dataWritten);
                }
            }
        }
    }

    /**
     * Opens a new stream channel for a shell command or service.
     */
    public int openStream(String destination, StreamListener listener) throws IOException {
        if (!mIsAdbReady.get()) {
            throw new IOException("ADB is not in ready state");
        }

        int localId = mNextChannelId.getAndIncrement();
        StreamChannel channel = new StreamChannel(localId, destination, listener);
        mActiveChannels.put(localId, channel);

        SystemLogger.getInstance().log("STREAM", "Requesting OPEN stream localId=" + localId + " dest=" + destination);
        AdbMessage openMsg = AdbMessage.createOpen(localId, destination);
        sendMessage(openMsg);
        return localId;
    }

    /**
     * Closes an active stream channel.
     */
    public void closeStream(int localId) {
        StreamChannel channel = mActiveChannels.remove(localId);
        if (channel != null && channel.isOpen) {
            channel.isOpen = false;
            try {
                AdbMessage clse = AdbMessage.createClse(channel.localId, channel.remoteId);
                sendMessage(clse);
            } catch (Exception e) {
                SystemLogger.getInstance().log("ERROR", "Error sending CLSE: " + e.getMessage());
            }
            if (channel.listener != null) {
                channel.listener.onStreamClosed(localId);
            }
        }
    }

    /**
     * Cleanly terminates the ADB connection, closes active streams, releases interfaces.
     */
    public synchronized void disconnect(String reason) {
        if (!mIsRunning.get() && mUsbConnection == null) {
            return;
        }
        SystemLogger.getInstance().log("USB", "Disconnecting ADB: " + reason);

        mIsRunning.set(false);
        mIsAdbReady.set(false);

        // Close all active channels
        for (StreamChannel channel : mActiveChannels.values()) {
            try {
                if (channel.isOpen && mUsbConnection != null) {
                    AdbMessage clse = AdbMessage.createClse(channel.localId, channel.remoteId);
                    sendMessage(clse);
                }
            } catch (Exception ignored) {}
            if (channel.listener != null) {
                channel.listener.onStreamClosed(channel.localId);
            }
        }
        mActiveChannels.clear();

        // Release USB interface and connection
        if (mUsbConnection != null) {
            try {
                if (mUsbInterface != null) {
                    mUsbConnection.releaseInterface(mUsbInterface);
                }
                mUsbConnection.close();
            } catch (Exception e) {
                SystemLogger.getInstance().log("ERROR", "Error closing USB connection: " + e.getMessage());
            }
            mUsbConnection = null;
        }

        if (mReaderThread != null && mReaderThread.isAlive()) {
            mReaderThread.interrupt();
        }
        mReaderThread = null;
        mUsbInterface = null;
        mEndpointIn = null;
        mEndpointOut = null;
        mUsbDevice = null;

        postAdbDisconnected(reason);
    }

    public boolean isConnectedAndReady() {
        return mIsAdbReady.get() && mIsRunning.get();
    }

    public UsbDevice getConnectedDevice() {
        return mUsbDevice;
    }

    // Callbacks to main thread
    private void postUsbPermissionRequired(final UsbDevice device) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onUsbPermissionRequired(device); }
        });
    }

    private void postAdbConnecting() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onAdbConnecting(); }
        });
    }

    private void postAdbAuthRequired() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onAdbAuthRequired(); }
        });
    }

    private void postAdbConnected(final String banner) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onAdbConnected(banner); }
        });
    }

    private void postAdbDisconnected(final String reason) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onAdbDisconnected(reason); }
        });
    }

    private void postError(final String error) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() { mListener.onError(error); }
        });
    }
}
