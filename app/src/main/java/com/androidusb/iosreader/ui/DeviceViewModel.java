package com.androidusb.iosreader.ui;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.protocol.LockdowndClient;
import com.androidusb.iosreader.ssl.PairRecord;
import com.androidusb.iosreader.ssl.PairRecordManager;
import com.androidusb.iosreader.usb.ConnectionState;
import com.androidusb.iosreader.usb.UsbMuxConnection;
import com.androidusb.iosreader.util.Logger;
import com.androidusb.iosreader.util.RetryHelper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ViewModel that manages iOS device connection state, pairing, and SSL session.
 *
 * Connection flow:
 * 1. USB open + usbmux connect to lockdownd
 * 2. QueryType (plaintext validation)
 * 3. Pair or validate existing pair record
 * 4. StartSession (enables SSL/TLS)
 * 5. GetDeviceInfo (encrypted)
 */
public class DeviceViewModel extends ViewModel {
    private static final String TAG = "DeviceViewModel";
    private static final int MAX_CONNECT_RETRIES = 3;
    private static final int MAX_READ_RETRIES = 2;
    private static final long PAIR_DIALOG_POLL_MS = 2000;
    private static final int PAIR_DIALOG_MAX_POLLS = 30; // 60 seconds max

    private UsbMuxConnection muxConnection;
    private volatile LockdowndClient lockdowndClient;
    private PairRecordManager pairRecordManager;
    private volatile boolean cleared = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<ConnectionState> connectionState = new MutableLiveData<>(ConnectionState.IDLE);
    private final MutableLiveData<iOSDeviceInfo> deviceInfo = new MutableLiveData<>();
    private final MutableLiveData<String> statusText = new MutableLiveData<>("");
    private final MutableLiveData<String> errorDetail = new MutableLiveData<>();
    private final MutableLiveData<Boolean> pairingRequired = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> sslActive = new MutableLiveData<>(false);

    public LiveData<ConnectionState> getConnectionState() { return connectionState; }
    public LiveData<iOSDeviceInfo> getDeviceInfo() { return deviceInfo; }
    public LiveData<String> getStatusText() { return statusText; }
    public LiveData<String> getErrorDetail() { return errorDetail; }
    public LiveData<Boolean> getPairingRequired() { return pairingRequired; }
    public LiveData<Boolean> getSSLActive() { return sslActive; }

    /**
     * Initialize with UsbManager and application context.
     */
    public void init(UsbManager usbManager, Context appContext) {
        if (muxConnection == null) {
            muxConnection = new UsbMuxConnection(usbManager);
        }
        if (pairRecordManager == null) {
            pairRecordManager = new PairRecordManager(appContext);
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
        pairingRequired.setValue(false);
    }

    public void setError(String title, String detail) {
        connectionState.setValue(ConnectionState.ERROR);
        statusText.setValue(title);
        errorDetail.setValue(detail);
    }

    public boolean isConnectionHealthy() {
        return muxConnection != null && muxConnection.isConnected() && lockdowndClient != null;
    }

    /**
     * Connect to the iOS device with full pairing and SSL.
     */
    public void connect() {
        ConnectionState current = connectionState.getValue();
        if (current != null && current.isActive()) return;

        connectionState.setValue(ConnectionState.CONNECTING);
        errorDetail.setValue(null);
        pairingRequired.setValue(false);

        executor.execute(() -> {
            if (cleared) return;
            try {
                // Phase 1: USB + usbmux connect
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

                // Phase 2: Validate lockdownd
                LockdowndClient client = new LockdowndClient(muxConnection);
                if (!client.queryType()) {
                    muxConnection.close();
                    throw new IOException("lockdownd validation failed");
                }
                lockdowndClient = client;

                // Phase 3: Pair + SSL session
                boolean sslReady = establishSSLSession(client);
                sslActive.postValue(sslReady);

                connectionState.postValue(ConnectionState.CONNECTED);

                // Phase 4: Auto-read device info
                readDeviceInfoInternal();

            } catch (IOException e) {
                Logger.e(TAG, "Connection failed", e);
                if (lockdowndClient != null) lockdowndClient.closeSSL();
                muxConnection.close();
                connectionState.postValue(ConnectionState.ERROR);
                errorDetail.postValue(e.getMessage());
            }
        });
    }

    /**
     * Attempt to pair with the device and start an SSL session.
     * Returns true if SSL was established, false if falling back to plaintext.
     */
    private boolean establishSSLSession(LockdowndClient client) throws IOException {
        try {
            // Get device's public key
            byte[] devicePublicKey = client.getDevicePublicKey();

            // Get UDID for pair record lookup
            String udid = getDeviceUDID(client);

            if (udid == null) {
                Logger.w(TAG, "Cannot determine UDID, skipping SSL");
                return false;
            }

            PairRecord pairRecord;
            if (pairRecordManager.hasPairRecord(udid)) {
                // Try existing pair record
                pairRecord = pairRecordManager.loadPairRecord(udid);
                if (!tryValidateAndStartSession(client, pairRecord)) {
                    // Pair record is stale, re-pair
                    pairRecordManager.deletePairRecord(udid);
                    pairRecord = performPairing(client, udid, devicePublicKey);
                }
            } else {
                pairRecord = performPairing(client, udid, devicePublicKey);
            }

            if (pairRecord != null) {
                // Start SSL session
                client.startSession(pairRecord);
                Logger.i(TAG, "SSL session active for UDID: " + udid);
                return true;
            }

            return false;

        } catch (Exception e) {
            Logger.w(TAG, "SSL setup failed, continuing without SSL: " + e.getMessage());
            return false;
        }
    }

    /**
     * Try to validate existing pair record and start session.
     */
    private boolean tryValidateAndStartSession(LockdowndClient client, PairRecord record) {
        try {
            return client.validatePair(record);
        } catch (IOException e) {
            Logger.w(TAG, "ValidatePair failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Perform the full pairing ceremony with the device.
     */
    private PairRecord performPairing(LockdowndClient client, String udid, byte[] devicePublicKey)
            throws Exception {
        PairRecord record = pairRecordManager.generatePairRecord(udid, devicePublicKey);

        LockdowndClient.PairResult result = client.pair(record);

        switch (result) {
            case SUCCESS:
                Logger.i(TAG, "Pairing succeeded");
                return record;

            case WAITING_FOR_USER:
                pairingRequired.postValue(true);
                // Poll for user to tap "Trust" on the device
                for (int i = 0; i < PAIR_DIALOG_MAX_POLLS && !cleared; i++) {
                    try {
                        Thread.sleep(PAIR_DIALOG_POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Pairing interrupted");
                    }
                    LockdowndClient.PairResult retry = client.pair(record);
                    if (retry == LockdowndClient.PairResult.SUCCESS) {
                        pairingRequired.postValue(false);
                        return record;
                    }
                    if (retry == LockdowndClient.PairResult.USER_DENIED) {
                        pairingRequired.postValue(false);
                        throw new IOException("User denied pairing on the device");
                    }
                }
                pairingRequired.postValue(false);
                throw new IOException("Pairing timeout: user did not respond");

            case PASSWORD_PROTECTED:
                throw new IOException("Device is locked. Please unlock it and try again.");

            case USER_DENIED:
                throw new IOException("Pairing was denied on the device");

            default:
                throw new IOException("Unexpected pairing result: " + result);
        }
    }

    /**
     * Get device UDID via plaintext lockdownd query.
     */
    private String getDeviceUDID(LockdowndClient client) {
        try {
            Map<String, Object> resp = client.getValue(null, "UniqueDeviceID");
            Object value = resp.get("Value");
            if (value instanceof String) {
                return (String) value;
            }
        } catch (IOException e) {
            Logger.w(TAG, "Failed to get UDID: " + e.getMessage());
        }
        return null;
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

            iOSDeviceInfo info = RetryHelper.execute(MAX_READ_RETRIES, attempt -> {
                if (cleared) throw new IOException("ViewModel cleared");
                return client.getDeviceInfo();
            });

            deviceInfo.postValue(info);
            connectionState.postValue(ConnectionState.CONNECTED);

        } catch (IOException e) {
            Logger.e(TAG, "Failed to read device info", e);

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

    public void reconnect() {
        if (lockdowndClient != null) lockdowndClient.closeSSL();
        lockdowndClient = null;
        if (muxConnection != null) muxConnection.close();
        sslActive.postValue(false);
        connect();
    }

    public void disconnect() {
        if (lockdowndClient != null) lockdowndClient.closeSSL();
        lockdowndClient = null;
        if (muxConnection != null) muxConnection.close();
        connectionState.postValue(ConnectionState.DISCONNECTED);
        errorDetail.postValue(null);
        deviceInfo.postValue(null);
        sslActive.postValue(false);
        pairingRequired.postValue(false);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cleared = true;
        if (lockdowndClient != null) lockdowndClient.closeSSL();
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
