package com.androidusb.iosreader.ui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.androidusb.iosreader.R;
import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.usb.ConnectionState;
import com.androidusb.iosreader.util.DeviceInfoExporter;
import com.androidusb.iosreader.util.Logger;

/**
 * Main activity that handles USB device detection and displays iOS device info.
 * Delegates connection logic to DeviceViewModel for lifecycle safety.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.androidusb.iosreader.USB_PERMISSION";
    private static final int APPLE_VENDOR_ID = 0x05AC;

    private UsbManager usbManager;
    private DeviceViewModel viewModel;

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
                    viewModel.connect();
                } else {
                    viewModel.setError(
                            getString(R.string.usb_permission_denied),
                            getString(R.string.usb_permission_hint));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Logger.i(TAG, "USB device attached");
                checkForDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Logger.i(TAG, "USB device detached");
                viewModel.disconnect();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        viewModel = new ViewModelProvider(this).get(DeviceViewModel.class);
        viewModel.init(usbManager, getApplicationContext());

        initViews();
        observeViewModel();
        registerUsbReceiver();

        // Check if launched by USB device attachment
        if (getIntent() != null) {
            UsbDevice device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && device.getVendorId() == APPLE_VENDOR_ID) {
                viewModel.setDevice(device);
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

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnRefresh.setOnClickListener(v -> viewModel.readDeviceInfo());
        btnShare.setOnClickListener(v -> {
            iOSDeviceInfo info = viewModel.getDeviceInfo().getValue();
            if (info != null) DeviceInfoExporter.share(this, info);
        });
    }

    private void onConnectClicked() {
        ConnectionState state = viewModel.getConnectionState().getValue();
        if (state == ConnectionState.ERROR && !viewModel.isConnectionHealthy()) {
            // Reconnect after a failure
            viewModel.reconnect();
        } else {
            checkForDevice();
        }
    }

    private void observeViewModel() {
        viewModel.getConnectionState().observe(this, this::onStateChanged);
        viewModel.getDeviceInfo().observe(this, this::displayDeviceInfo);
        viewModel.getErrorDetail().observe(this, detail -> {
            if (detail != null && !detail.isEmpty()) {
                tvErrorDetail.setText(detail);
                tvErrorDetail.setVisibility(View.VISIBLE);
            } else {
                tvErrorDetail.setVisibility(View.GONE);
            }
        });
        viewModel.getPairingRequired().observe(this, required -> {
            if (Boolean.TRUE.equals(required)) {
                tvStatus.setText(R.string.pairing_tap_trust);
            }
        });
        viewModel.getSSLActive().observe(this, active -> {
            if (Boolean.TRUE.equals(active)) {
                tvConnectionInfo.setText(R.string.connected_ssl);
            }
        });
    }

    private void onStateChanged(ConnectionState newState) {
        // Update status text based on state
        switch (newState) {
            case IDLE:
            case DISCONNECTED:
                tvStatus.setText(R.string.disconnected);
                btnConnect.setEnabled(true);
                btnConnect.setText(R.string.btn_connect);
                btnRefresh.setVisibility(View.GONE);
                btnShare.setVisibility(View.GONE);
                deviceInfoContainer.setVisibility(View.GONE);
                showProgress(false);
                tvConnectionInfo.setText(R.string.no_device);
                break;
            case CONNECTING:
                tvStatus.setText(R.string.connecting);
                btnConnect.setEnabled(false);
                showProgress(true);
                break;
            case CONNECTED:
                tvStatus.setText(R.string.connected);
                btnConnect.setEnabled(false);
                btnRefresh.setVisibility(View.VISIBLE);
                showProgress(false);
                break;
            case READING:
                tvStatus.setText(R.string.reading_info);
                showProgress(true);
                break;
            case ERROR:
                tvStatus.setText(R.string.connection_failed);
                btnConnect.setEnabled(true);
                btnConnect.setText(R.string.btn_reconnect);
                showProgress(false);
                break;
        }
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void checkForDevice() {
        UsbDevice device = viewModel.findAppleDevice();
        if (device != null) {
            String productName = device.getProductName();
            tvConnectionInfo.setText(getString(R.string.device_found,
                    productName != null ? productName : "Apple Device"));
            requestPermissionAndConnect(device);
        } else {
            viewModel.setIdle();
            tvConnectionInfo.setText(R.string.no_device);
        }
    }

    private void requestPermissionAndConnect(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            viewModel.connect();
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            tvStatus.setText(R.string.requesting_usb_permission);
        }
    }

    private void displayDeviceInfo(iOSDeviceInfo info) {
        if (info == null) return;
        deviceInfoContainer.setVisibility(View.VISIBLE);
        btnShare.setVisibility(View.VISIBLE);

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
                ? info.getBatteryLevel() + "%" + (info.isBatteryCharging()
                    ? " (" + getString(R.string.battery_charging) + ")" : "")
                : null;
        setText(tvBattery, batteryText);
    }

    // --- UI helpers ---

    private void showProgress(boolean visible) {
        progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private String formatVersion(String version, String build) {
        if (version == null) return null;
        if (build != null) return version + " (" + build + ")";
        return version;
    }

    private void setText(TextView tv, String value) {
        tv.setText(value != null && !value.isEmpty() ? value : "--");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && device.getVendorId() == APPLE_VENDOR_ID) {
            viewModel.setDevice(device);
            requestPermissionAndConnect(device);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
    }
}
