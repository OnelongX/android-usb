package com.androidusb.iosreader.util;

import android.content.Context;
import android.content.Intent;

import com.androidusb.iosreader.R;
import com.androidusb.iosreader.model.iOSDeviceInfo;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Export device info as JSON or shareable text.
 * Uses Android string resources for localized labels.
 */
public final class DeviceInfoExporter {

    private DeviceInfoExporter() {}

    /**
     * Build a localized text summary of device info.
     */
    public static String toText(Context context, iOSDeviceInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(context.getString(R.string.device_info_title)).append(" ===\n");
        appendField(sb, context.getString(R.string.device_name), info.getDeviceName());
        appendField(sb, context.getString(R.string.device_model), info.getDisplayModel());
        appendField(sb, context.getString(R.string.ios_version), formatVersion(info));
        appendField(sb, context.getString(R.string.serial_number), info.getSerialNumber());
        appendField(sb, "UDID", info.getUniqueDeviceID());
        appendField(sb, context.getString(R.string.wifi_mac), info.getWifiAddress());
        appendField(sb, context.getString(R.string.bluetooth_mac), info.getBluetoothAddress());
        appendField(sb, "IMEI", info.getIMEI());
        appendField(sb, context.getString(R.string.phone_number), info.getPhoneNumber());
        appendField(sb, context.getString(R.string.battery_level), formatBattery(context, info));
        appendField(sb, context.getString(R.string.storage_info), info.getFormattedStorage());
        appendField(sb, context.getString(R.string.cpu_arch), info.getCpuArchitecture());
        appendField(sb, context.getString(R.string.hardware_model), info.getHardwareModel());
        return sb.toString();
    }

    public static String toJson(iOSDeviceInfo info) {
        try {
            JSONObject json = new JSONObject();
            json.put("deviceName", info.getDeviceName());
            json.put("model", info.getDisplayModel());
            json.put("modelNumber", info.getModelNumber());
            json.put("productType", info.getProductType());
            json.put("productVersion", info.getProductVersion());
            json.put("buildVersion", info.getBuildVersion());
            json.put("serialNumber", info.getSerialNumber());
            json.put("udid", info.getUniqueDeviceID());
            json.put("wifiAddress", info.getWifiAddress());
            json.put("bluetoothAddress", info.getBluetoothAddress());
            json.put("imei", info.getIMEI());
            json.put("phoneNumber", info.getPhoneNumber());
            json.put("batteryLevel", info.getBatteryLevel());
            json.put("batteryCharging", info.isBatteryCharging());
            json.put("totalDiskCapacity", info.getTotalDiskCapacity());
            json.put("availableDiskCapacity", info.getAvailableDiskCapacity());
            json.put("cpuArchitecture", info.getCpuArchitecture());
            json.put("hardwareModel", info.getHardwareModel());
            return json.toString(2);
        } catch (JSONException e) {
            return "{ \"error\": \"Failed to export\" }";
        }
    }

    public static void share(Context context, iOSDeviceInfo info) {
        String text = toText(context, info);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                context.getString(R.string.device_info_title) + " - " + info.getDeviceName());
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(shareIntent,
                context.getString(R.string.btn_share)));
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private static String formatVersion(iOSDeviceInfo info) {
        String v = info.getProductVersion();
        String b = info.getBuildVersion();
        if (v == null) return null;
        return b != null ? v + " (" + b + ")" : v;
    }

    private static String formatBattery(Context context, iOSDeviceInfo info) {
        if (info.getBatteryLevel() <= 0) return null;
        String text = info.getBatteryLevel() + "%";
        if (info.isBatteryCharging()) {
            text += " (" + context.getString(R.string.battery_charging) + ")";
        }
        return text;
    }
}
