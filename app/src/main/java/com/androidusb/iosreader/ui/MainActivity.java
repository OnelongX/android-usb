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
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.androidusb.iosreader.R;
import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.protocol.LockdowndClient;
import com.androidusb.iosreader.usb.UsbMuxConnection;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main activity that handles USB device detection and displays iOS device info.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.androidusb.iosreader.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbMuxConnection muxConnection;
    private LockdowndClient lockdowndClient;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // UI elements
    private TextView tvStatus;
    private TextView tvConnectionInfo;
    private Button btnConnect;
    private Button btnRefresh;
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
                    Log.i(TAG, "USB permission granted");
                    connectToDevice();
                } else {
                    showStatus("USB 权限被拒绝", false);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.i(TAG, "USB device attached");
                checkForDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "USB device detached");
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
            if (device != null) {
                muxConnection.setDevice(device);
                requestPermissionAndConnect(device);
                return;
            }
        }

        checkForDevice();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvConnectionInfo = findViewById(R.id.tvConnectionInfo);
        btnConnect = findViewById(R.id.btnConnect);
        btnRefresh = findViewById(R.id.btnRefresh);
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

        btnConnect.setOnClickListener(v -> checkForDevice());
        btnRefresh.setOnClickListener(v -> readDeviceInfo());
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
    }

    private void checkForDevice() {
        UsbDevice device = muxConnection.findAppleDevice();
        if (device != null) {
            showStatus("发现 Apple 设备", false);
            tvConnectionInfo.setText("发现设备: " + device.getProductName());
            requestPermissionAndConnect(device);
        } else {
            showStatus(getString(R.string.disconnected), false);
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
            showStatus("请求 USB 权限...", false);
        }
    }

    private void connectToDevice() {
        showStatus(getString(R.string.connecting), false);
        btnConnect.setEnabled(false);

        executor.execute(() -> {
            try {
                muxConnection.open();
                muxConnection.connectToLockdownd();
                lockdowndClient = new LockdowndClient(muxConnection);

                boolean valid = lockdowndClient.queryType();
                if (!valid) {
                    throw new IOException("lockdownd QueryType failed");
                }

                mainHandler.post(() -> {
                    showStatus(getString(R.string.connected), true);
                    btnRefresh.setVisibility(View.VISIBLE);
                    readDeviceInfo();
                });

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                mainHandler.post(() -> {
                    showStatus("连接失败: " + e.getMessage(), false);
                    btnConnect.setEnabled(true);
                });
            }
        });
    }

    private void readDeviceInfo() {
        showStatus(getString(R.string.reading_info), true);

        executor.execute(() -> {
            try {
                iOSDeviceInfo info = lockdowndClient.getDeviceInfo();
                mainHandler.post(() -> displayDeviceInfo(info));
            } catch (IOException e) {
                Log.e(TAG, "Failed to read device info", e);
                mainHandler.post(() -> {
                    showStatus("读取信息失败: " + e.getMessage(), true);
                    Toast.makeText(this, "读取失败，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void displayDeviceInfo(iOSDeviceInfo info) {
        deviceInfoContainer.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.connected), true);

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

    private String formatVersion(String version, String build) {
        if (version == null) return null;
        if (build != null) return version + " (" + build + ")";
        return version;
    }

    private void setText(TextView tv, String value) {
        tv.setText(value != null && !value.isEmpty() ? value : "--");
    }

    private void showStatus(String message, boolean connected) {
        tvStatus.setText(message);
        btnConnect.setEnabled(!connected);
    }

    private void onDeviceDisconnected() {
        if (muxConnection != null) {
            muxConnection.close();
        }
        lockdowndClient = null;

        showStatus(getString(R.string.disconnected), false);
        tvConnectionInfo.setText(R.string.no_device);
        btnRefresh.setVisibility(View.GONE);
        deviceInfoContainer.setVisibility(View.GONE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null) {
            muxConnection.setDevice(device);
            requestPermissionAndConnect(device);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        if (muxConnection != null) {
            muxConnection.close();
        }
        executor.shutdown();
    }
}
