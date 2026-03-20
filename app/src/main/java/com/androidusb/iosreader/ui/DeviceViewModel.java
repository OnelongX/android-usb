package com.androidusb.iosreader.ui;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.protocol.LockdowndClient;
import com.androidusb.iosreader.usb.ConnectionState;
import com.androidusb.iosreader.usb.UsbMuxConnection;
import com.androidusb.iosreader.util.Logger;
import com.androidusb.iosreader.util.RetryHelper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ViewModel that manages iOS device connection state and device info.
 * Survives configuration changes (rotation) and owns the USB connection lifecycle.
 * Supports auto-reconnection on transient USB failures.
 */
public class DeviceViewModel extends ViewModel {
    private static final String TAG = "DeviceViewModel";
    private static final int MAX_CONNECT_RETRIES = 3;
    private static final int MAX_READ_RETRIES = 2;

    private UsbMuxConnection muxConnection;
    private volatile LockdowndClient lockdowndClient;
    private volatile boolean cleared = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<ConnectionState> connectionState = new MutableLiveData<>(ConnectionState.IDLE);
    private final MutableLiveData<iOSDeviceInfo> deviceInfo = new MutableLiveData<>();
    private final MutableLiveData<String> statusText = new MutableLiveData<>("");
    private final MutableLiveData<String> errorDetail = new MutableLiveData<>();

    public LiveData<ConnectionState> getConnectionState() { return connectionState; }
    public LiveData<iOSDeviceInfo> getDeviceInfo() { return deviceInfo; }
    public LiveData<String> getStatusText() { return statusText; }
    public LiveData<String> getErrorDetail() { return errorDetail; }

    /**
     * Initialize with UsbManager. Safe to call multiple times (idempotent).
     */
    public void init(UsbManager usbManager) {
        if (muxConnection == null) {
            muxConnection = new UsbMuxConnection(usbManager);
        }
    }

    public UsbDevice findAppleDevice() {
        return muxConnection != null ? muxConnection.findAppleDevice() : null;
    }

    public void setDevice(UsbDevice device) {
        if (muxConnection != null) muxConnection.setDevice(device);
    }

    public void setIdle() {
        connectionState.setValue(ConnectionState.IDLE);
        errorDetail.setValue(null);
    }

    public void setError(String title, String detail) {
        connectionState.setValue(ConnectionState.ERROR);
        statusText.setValue(title);
        errorDetail.setValue(detail);
    }

    /**
     * Check if the USB connection is still alive by verifying the underlying connection.
     */
    public boolean isConnectionHealthy() {
        return muxConnection != null && muxConnection.isConnected() && lockdowndClient != null;
    }

    /**
     * Connect to the iOS device with retry logic.
     */
    public void connect() {
        ConnectionState current = connectionState.getValue();
        if (current != null && current.isActive()) return;

        connectionState.setValue(ConnectionState.CONNECTING);
        errorDetail.setValue(null);

        executor.execute(() -> {
            if (cleared) return;
            try {
                RetryHelper.execute(MAX_CONNECT_RETRIES, attempt -> {
                    if (cleared) throw new IOException("ViewModel cleared");

                    muxConnection.open();
                    try {
                        muxConnection.connectToLockdownd();
                    } catch (IOException e) {
                        muxConnection.close();
                        throw e;
                    }
                    return true;
                });

                LockdowndClient client = new LockdowndClient(muxConnection);
                if (!client.queryType()) {
                    muxConnection.close();
                    throw new IOException("lockdownd validation failed: device may need to trust this device");
                }

                lockdowndClient = client;
                connectionState.postValue(ConnectionState.CONNECTED);

                // Auto-read device info on first connect
                readDeviceInfoInternal();

            } catch (IOException e) {
                Logger.e(TAG, "Connection failed", e);
                muxConnection.close();
                connectionState.postValue(ConnectionState.ERROR);
                errorDetail.postValue(e.getMessage());
            }
        });
    }

    /**
     * Read device info from the connected iOS device.
     */
    public void readDeviceInfo() {
        ConnectionState current = connectionState.getValue();
        if (current != ConnectionState.CONNECTED || lockdowndClient == null) return;

        connectionState.setValue(ConnectionState.READING);
        errorDetail.setValue(null);

        executor.execute(this::readDeviceInfoInternal);
    }

    private void readDeviceInfoInternal() {
        LockdowndClient client = lockdowndClient;
        if (client == null || cleared) return;

        try {
            connectionState.postValue(ConnectionState.READING);

            // Retry reads to handle transient USB transfer failures
            iOSDeviceInfo info = RetryHelper.execute(MAX_READ_RETRIES, attempt -> {
                if (cleared) throw new IOException("ViewModel cleared");
                return client.getDeviceInfo();
            });

            deviceInfo.postValue(info);
            connectionState.postValue(ConnectionState.CONNECTED);

        } catch (IOException e) {
            Logger.e(TAG, "Failed to read device info", e);

            // If the USB connection died, transition to error for reconnection
            if (!muxConnection.isConnected()) {
                lockdowndClient = null;
                connectionState.postValue(ConnectionState.ERROR);
                errorDetail.postValue("USB connection lost: " + e.getMessage());
            } else {
                connectionState.postValue(ConnectionState.CONNECTED);
                errorDetail.postValue(e.getMessage());
            }
        }
    }

    /**
     * Reconnect after a connection loss. Closes existing connection first.
     */
    public void reconnect() {
        lockdowndClient = null;
        if (muxConnection != null) muxConnection.close();
        connect();
    }

    /**
     * Disconnect and clean up USB resources.
     */
    public void disconnect() {
        lockdowndClient = null;
        if (muxConnection != null) muxConnection.close();
        connectionState.postValue(ConnectionState.DISCONNECTED);
        errorDetail.postValue(null);
        deviceInfo.postValue(null);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cleared = true;
        lockdowndClient = null;
        if (muxConnection != null) muxConnection.close();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
