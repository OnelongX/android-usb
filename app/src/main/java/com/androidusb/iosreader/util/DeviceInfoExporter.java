package com.androidusb.iosreader.util;

import android.content.Context;
import android.content.Intent;

import com.androidusb.iosreader.model.iOSDeviceInfo;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Export device info as JSON or shareable text.
 */
public final class DeviceInfoExporter {

    private DeviceInfoExporter() {}

    public static String toText(iOSDeviceInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== iOS 设备信息 ===\n");
        appendField(sb, "设备名称", info.getDeviceName());
        appendField(sb, "设备型号", info.getDisplayModel());
        appendField(sb, "iOS 版本", formatVersion(info));
        appendField(sb, "序列号", info.getSerialNumber());
        appendField(sb, "UDID", info.getUniqueDeviceID());
        appendField(sb, "WiFi MAC", info.getWifiAddress());
        appendField(sb, "蓝牙 MAC", info.getBluetoothAddress());
        appendField(sb, "IMEI", info.getIMEI());
        appendField(sb, "电话号码", info.getPhoneNumber());
        appendField(sb, "电池电量", formatBattery(info));
        appendField(sb, "存储", info.getFormattedStorage());
        appendField(sb, "CPU 架构", info.getCpuArchitecture());
        appendField(sb, "硬件型号", info.getHardwareModel());
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
        String text = toText(info);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "iOS 设备信息 - " + info.getDeviceName());
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(shareIntent, "分享设备信息"));
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty() && !"未知".equals(value)) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private static String formatVersion(iOSDeviceInfo info) {
        String v = info.getProductVersion();
        String b = info.getBuildVersion();
        if (v == null) return null;
        return b != null ? v + " (" + b + ")" : v;
    }

    private static String formatBattery(iOSDeviceInfo info) {
        if (info.getBatteryLevel() <= 0) return null;
        return info.getBatteryLevel() + "%" + (info.isBatteryCharging() ? " (充电中)" : "");
    }
}
