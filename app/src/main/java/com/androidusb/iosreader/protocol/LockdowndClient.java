package com.androidusb.iosreader.protocol;

import android.util.Log;

import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.usb.UsbMuxConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for Apple's lockdownd protocol, which runs on iOS devices
 * and provides access to device information and service management.
 *
 * The protocol flow is:
 * 1. Query device type (QueryType)
 * 2. Get device values (GetValue)
 * 3. Optionally start services (StartService)
 *
 * Messages are XML plists prefixed with a 4-byte big-endian length header.
 */
public class LockdowndClient {
    private static final String TAG = "LockdowndClient";

    private final UsbMuxConnection usbConnection;

    public LockdowndClient(UsbMuxConnection usbConnection) {
        this.usbConnection = usbConnection;
    }

    /**
     * Validate the connection by sending a QueryType request.
     * lockdownd should respond with "com.apple.mobile.lockdown".
     */
    public boolean queryType() throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", "android-usb-reader");
        request.put("Request", "QueryType");

        Map<String, Object> response = sendAndReceive(request);
        String type = (String) response.get("Type");
        Log.i(TAG, "QueryType response: " + type);
        return "com.apple.mobile.lockdown".equals(type);
    }

    /**
     * Retrieve all device values from lockdownd.
     */
    public iOSDeviceInfo getDeviceInfo() throws IOException {
        iOSDeviceInfo info = new iOSDeviceInfo();

        // Get all values (no domain, no key = return everything)
        Map<String, Object> allValues = getValue(null, null);

        if (allValues != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) allValues.get("Value");
            if (value == null) value = allValues;

            info.setDeviceName(getStr(value, "DeviceName"));
            info.setModelNumber(getStr(value, "ModelNumber"));
            info.setProductType(getStr(value, "ProductType"));
            info.setProductVersion(getStr(value, "ProductVersion"));
            info.setBuildVersion(getStr(value, "BuildVersion"));
            info.setSerialNumber(getStr(value, "SerialNumber"));
            info.setUniqueDeviceID(getStr(value, "UniqueDeviceID"));
            info.setWifiAddress(getStr(value, "WiFiAddress"));
            info.setBluetoothAddress(getStr(value, "BluetoothAddress"));
            info.setPhoneNumber(getStr(value, "PhoneNumber"));
            info.setIMEI(getStr(value, "InternationalMobileEquipmentIdentity"));
            info.setCpuArchitecture(getStr(value, "CPUArchitecture"));
            info.setHardwareModel(getStr(value, "HardwareModel"));
        }

        // Get battery info from a specific domain
        try {
            Map<String, Object> batteryResult = getValue("com.apple.mobile.battery", null);
            if (batteryResult != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bv = (Map<String, Object>) batteryResult.get("Value");
                if (bv == null) bv = batteryResult;

                Object level = bv.get("BatteryCurrentCapacity");
                if (level instanceof Long) info.setBatteryLevel(((Long) level).intValue());

                Object charging = bv.get("BatteryIsCharging");
                if (charging instanceof Boolean) info.setBatteryCharging((Boolean) charging);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to get battery info", e);
        }

        // Get disk usage info
        try {
            Map<String, Object> diskResult = getValue("com.apple.disk_usage", null);
            if (diskResult != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dv = (Map<String, Object>) diskResult.get("Value");
                if (dv == null) dv = diskResult;

                Object total = dv.get("TotalDiskCapacity");
                if (total instanceof Long) info.setTotalDiskCapacity((Long) total);

                Object avail = dv.get("AmountDataAvailable");
                if (avail instanceof Long) info.setAvailableDiskCapacity((Long) avail);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to get disk info", e);
        }

        return info;
    }

    /**
     * Send a GetValue request to lockdownd.
     * @param domain Optional domain (null for default)
     * @param key Optional specific key (null for all values in domain)
     */
    public Map<String, Object> getValue(String domain, String key) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", "android-usb-reader");
        request.put("Request", "GetValue");
        if (domain != null) request.put("Domain", domain);
        if (key != null) request.put("Key", key);

        return sendAndReceive(request);
    }

    /**
     * Send a plist request and receive + parse the plist response.
     */
    private Map<String, Object> sendAndReceive(Map<String, Object> request) throws IOException {
        String xml = PlistBuilder.buildPlist(request);
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);

        // Send: 4-byte big-endian length prefix + XML payload
        ByteBuffer lengthBuf = ByteBuffer.allocate(4);
        lengthBuf.order(ByteOrder.BIG_ENDIAN);
        lengthBuf.putInt(xmlBytes.length);

        byte[] packet = new byte[4 + xmlBytes.length];
        System.arraycopy(lengthBuf.array(), 0, packet, 0, 4);
        System.arraycopy(xmlBytes, 0, packet, 4, xmlBytes.length);

        Log.d(TAG, "Sending lockdownd request: " + request.get("Request"));
        usbConnection.sendRaw(packet);

        // Receive: response also has 4-byte BE length prefix
        byte[] response = usbConnection.receiveRaw();
        if (response == null || response.length < 4) {
            throw new IOException("Empty lockdownd response");
        }

        // The response from receiveRaw already includes the usbmux framing,
        // but after connect, we're in raw TCP mode, so the first 4 bytes
        // are the lockdownd plist length
        int plistLen = ByteBuffer.wrap(response, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (plistLen <= 0 || plistLen > response.length - 4) {
            // Try treating entire response as plist (in case framing differs)
            String responseXml = new String(response, StandardCharsets.UTF_8);
            if (responseXml.contains("<?xml")) {
                return PlistParser.parse(responseXml);
            }
            throw new IOException("Invalid lockdownd response length: " + plistLen);
        }

        String responseXml = new String(response, 4, plistLen, StandardCharsets.UTF_8);
        Log.d(TAG, "Received lockdownd response (" + plistLen + " bytes)");

        Map<String, Object> result = PlistParser.parse(responseXml);

        // Check for errors
        String error = getStr(result, "Error");
        if (error != null && !error.isEmpty()) {
            Log.w(TAG, "lockdownd error: " + error);
        }

        return result;
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : null;
    }
}
