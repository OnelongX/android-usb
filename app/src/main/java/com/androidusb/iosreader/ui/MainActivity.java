package com.androidusb.iosreader.ui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.androidusb.iosreader.R;
import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.protocol.LockdowndClient;
import com.androidusb.iosreader.usb.ConnectionState;
import com.androidusb.iosreader.usb.UsbMuxConnection;
import com.androidusb.iosreader.util.DeviceInfoExporter;
import com.androidusb.iosreader.util.Logger;
import com.androidusb.iosreader.util.RetryHelper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main activity that handles USB device detection and displays iOS device info.
 * Uses a ConnectionState machine to prevent race conditions and invalid state transitions.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.androidusb.iosreader.USB_PERMISSION";
    private static final int MAX_CONNECT_RETRIES = 3;

    private UsbManager usbManager;
    private UsbMuxConnection muxConnection;
    private volatile LockdowndClient lockdowndClient;
    private volatile ConnectionState state = ConnectionState.IDLE;
    private volatile boolean isDestroyed = false;
    private iOSDeviceInfo lastDeviceInfo;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();

    // UI elements
    private ProgressBar progressBar;
    private TextView tvStatus;
    private TextView tvConnectionInfo;
    private TextView tvErrorDetail;
    private Button btnConnect;
    private Button btnRefresh;
    private Button btnShare;
    private LinearLayout deviceInfoContainer;
    private TextView tvDeviceName, tvDeviceModel, tvIOSVersion, tvSerialNumber;
    private TextView tvUDID, tvWifiMAC, tvBluetoothMAC, tvBattery;
    private TextView tvIMEI, tvPhoneNumber, tvStorage;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    Logger.i(TAG, "USB permission granted");
                    connectToDevice();
                } else {
                    transitionTo(ConnectionState.ERROR);
                    showError("USB 权限被拒绝", "请允许 USB 访问权限以连接 iOS 设备");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Logger.i(TAG, "USB device attached");
                checkForDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Logger.i(TAG, "USB device detached");
                onDeviceDisconnected();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        muxConnection = new UsbMuxConnection(usbManager);

        initViews();
        registerUsbReceiver();

        // Check if launched by USB device attachment
        if (getIntent() != null) {
            UsbDevice device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && device.getVendorId() == 0x05AC) {
                muxConnection.setDevice(device);
                requestPermissionAndConnect(device);
                return;
            }
        }

        checkForDevice();
    }

    private void initViews() {
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);
        tvConnectionInfo = findViewById(R.id.tvConnectionInfo);
        tvErrorDetail = findViewById(R.id.tvErrorDetail);
        btnConnect = findViewById(R.id.btnConnect);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnShare = findViewById(R.id.btnShare);
        deviceInfoContainer = findViewById(R.id.deviceInfoContainer);

        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvDeviceModel = findViewById(R.id.tvDeviceModel);
        tvIOSVersion = findViewById(R.id.tvIOSVersion);
        tvSerialNumber = findViewById(R.id.tvSerialNumber);
        tvUDID = findViewById(R.id.tvUDID);
        tvWifiMAC = findViewById(R.id.tvWifiMAC);
        tvBluetoothMAC = findViewById(R.id.tvBluetoothMAC);
        tvBattery = findViewById(R.id.tvBattery);
        tvIMEI = findViewById(R.id.tvIMEI);
        tvPhoneNumber = findViewById(R.id.tvPhoneNumber);
        tvStorage = findViewById(R.id.tvStorage);

        btnConnect.setOnClickListener(v -> {
            if (state.canConnect()) {
                checkForDevice();
            }
        });
        btnRefresh.setOnClickListener(v -> {
            if (state.canRead()) {
                readDeviceInfo();
            }
        });
        btnShare.setOnClickListener(v -> {
            if (lastDeviceInfo != null) {
                DeviceInfoExporter.share(this, lastDeviceInfo);
            }
        });
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
    }

    private void checkForDevice() {
        hideError();
        UsbDevice device = muxConnection.findAppleDevice();
        if (device != null) {
            String productName = device.getProductName();
            tvConnectionInfo.setText("发现设备: " + (productName != null ? productName : "Apple Device"));
            requestPermissionAndConnect(device);
        } else {
            transitionTo(ConnectionState.IDLE);
            tvConnectionInfo.setText(R.string.no_device);
        }
    }

    private void requestPermissionAndConnect(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            connectToDevice();
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(device, pi);
            tvStatus.setText("请求 USB 权限...");
        }
    }

    private void connectToDevice() {
        if (!transitionTo(ConnectionState.CONNECTING)) return;

        showProgress(true);
        hideError();
        tvStatus.setText(R.string.connecting);
        btnConnect.setEnabled(false);

        executor.execute(() -> {
            try {
                // Retry connection with exponential backoff
                RetryHelper.execute(MAX_CONNECT_RETRIES, attempt -> {
                    if (isDestroyed) throw new IOException("Activity destroyed");

                    // Update UI with retry progress
                    if (attempt > 1) {
                        final int a = attempt;
                        postToMain(() -> tvStatus.setText(
                                getString(R.string.retry_connecting, a, MAX_CONNECT_RETRIES)));
                    }

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
                boolean valid = client.queryType();
                if (!valid) {
                    muxConnection.close();
                    throw new IOException("lockdownd 验证失败：设备可能不支持或需要信任此设备");
                }

                lockdowndClient = client;
                transitionTo(ConnectionState.CONNECTED);

                postToMain(() -> {
                    showProgress(false);
                    tvStatus.setText(R.string.connected);
                    btnRefresh.setVisibility(View.VISIBLE);
                    readDeviceInfo();
                });

            } catch (IOException e) {
                Logger.e(TAG, "Connection failed", e);
                muxConnection.close();
                transitionTo(ConnectionState.ERROR);

                postToMain(() -> {
                    showProgress(false);
                    showError(getString(R.string.connection_failed), e.getMessage());
                    btnConnect.setEnabled(true);
                });
            }
        });
    }

    private void readDeviceInfo() {
        final LockdowndClient client = lockdowndClient;
        if (client == null || !transitionTo(ConnectionState.READING)) return;

        showProgress(true);
        hideError();
        tvStatus.setText(R.string.reading_info);

        executor.execute(() -> {
            try {
                iOSDeviceInfo info = client.getDeviceInfo();
                lastDeviceInfo = info;
                transitionTo(ConnectionState.CONNECTED);

                postToMain(() -> {
                    showProgress(false);
                    displayDeviceInfo(info);
                });

            } catch (IOException e) {
                Logger.e(TAG, "Failed to read device info", e);
                transitionTo(ConnectionState.CONNECTED);

                postToMain(() -> {
                    showProgress(false);
                    showError("读取信息失败", e.getMessage());
                });
            }
        });
    }

    private void displayDeviceInfo(iOSDeviceInfo info) {
        deviceInfoContainer.setVisibility(View.VISIBLE);
        btnShare.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.connected);

        setText(tvDeviceName, info.getDeviceName());
        setText(tvDeviceModel, info.getDisplayModel());
        setText(tvIOSVersion, formatVersion(info.getProductVersion(), info.getBuildVersion()));
        setText(tvSerialNumber, info.getSerialNumber());
        setText(tvUDID, info.getUniqueDeviceID());
        setText(tvWifiMAC, info.getWifiAddress());
        setText(tvBluetoothMAC, info.getBluetoothAddress());
        setText(tvIMEI, info.getIMEI());
        setText(tvPhoneNumber, info.getPhoneNumber());
        setText(tvStorage, info.getFormattedStorage());

        String batteryText = info.getBatteryLevel() > 0
                ? info.getBatteryLevel() + "%" + (info.isBatteryCharging() ? " (充电中)" : "")
                : null;
        setText(tvBattery, batteryText);
    }

    // --- State management ---

    private boolean transitionTo(ConnectionState newState) {
        synchronized (stateLock) {
            ConnectionState old = state;
            state = newState;
            Logger.d(TAG, "State: " + old + " → " + newState);
            postToMain(this::updateUIForState);
            return true;
        }
    }

    private void updateUIForState() {
        switch (state) {
            case IDLE:
            case DISCONNECTED:
                tvStatus.setText(R.string.disconnected);
                btnConnect.setEnabled(true);
                btnRefresh.setVisibility(View.GONE);
                showProgress(false);
                break;
            case ERROR:
                btnConnect.setEnabled(true);
                showProgress(false);
                break;
            case CONNECTING:
                btnConnect.setEnabled(false);
                break;
            case CONNECTED:
                btnConnect.setEnabled(false);
                btnRefresh.setVisibility(View.VISIBLE);
                showProgress(false);
                break;
            case READING:
                break;
        }
    }

    // --- UI helpers ---

    private void showProgress(boolean visible) {
        progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showError(String title, String detail) {
        tvStatus.setText(title);
        if (detail != null && !detail.isEmpty()) {
            tvErrorDetail.setText(detail);
            tvErrorDetail.setVisibility(View.VISIBLE);
        }
    }

    private void hideError() {
        tvErrorDetail.setVisibility(View.GONE);
    }

    private String formatVersion(String version, String build) {
        if (version == null) return null;
        if (build != null) return version + " (" + build + ")";
        return version;
    }

    private void setText(TextView tv, String value) {
        tv.setText(value != null && !value.isEmpty() ? value : "--");
    }

    private void postToMain(Runnable action) {
        if (!isDestroyed) mainHandler.post(action);
    }

    // --- Lifecycle ---

    private void onDeviceDisconnected() {
        if (muxConnection != null) muxConnection.close();
        lockdowndClient = null;
        transitionTo(ConnectionState.DISCONNECTED);

        tvConnectionInfo.setText(R.string.no_device);
        btnShare.setVisibility(View.GONE);
        deviceInfoContainer.setVisibility(View.GONE);
        hideError();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && device.getVendorId() == 0x05AC) {
            muxConnection.setDevice(device);
            requestPermissionAndConnect(device);
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        unregisterReceiver(usbReceiver);
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
