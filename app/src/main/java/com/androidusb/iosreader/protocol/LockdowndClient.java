package com.androidusb.iosreader.protocol;

import com.androidusb.iosreader.util.Logger;

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
    private static final String CLIENT_LABEL = "android-usb-reader";

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
        request.put("Label", CLIENT_LABEL);
        request.put("Request", "QueryType");

        Map<String, Object> response = sendAndReceive(request);
        Object type = response.get("Type");
        Logger.i(TAG, "QueryType response: " + type);
        return "com.apple.mobile.lockdown".equals(type);
    }

    /**
     * Retrieve all device values from lockdownd.
     */
    public iOSDeviceInfo getDeviceInfo() throws IOException {
        iOSDeviceInfo info = new iOSDeviceInfo();

        // Get all values (no domain, no key = return everything)
        Map<String, Object> allValues = getValue(null, null);
        Map<String, Object> value = extractValueDict(allValues);

        if (value != null) {
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
            Map<String, Object> bv = extractValueDict(getValue("com.apple.mobile.battery", null));
            if (bv != null) {
                info.setBatteryLevel(getInt(bv, "BatteryCurrentCapacity"));
                Object charging = bv.get("BatteryIsCharging");
                if (charging instanceof Boolean) info.setBatteryCharging((Boolean) charging);
            }
        } catch (IOException e) {
            Logger.w(TAG, "Failed to get battery info: " + e.getMessage());
        }

        // Get disk usage info
        try {
            Map<String, Object> dv = extractValueDict(getValue("com.apple.disk_usage", null));
            if (dv != null) {
                info.setTotalDiskCapacity(getLong(dv, "TotalDiskCapacity"));
                info.setAvailableDiskCapacity(getLong(dv, "AmountDataAvailable"));
            }
        } catch (IOException e) {
            Logger.w(TAG, "Failed to get disk info: " + e.getMessage());
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
        request.put("Label", CLIENT_LABEL);
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
        byte[] packet = new byte[4 + xmlBytes.length];
        packet[0] = (byte) (xmlBytes.length >> 24);
        packet[1] = (byte) (xmlBytes.length >> 16);
        packet[2] = (byte) (xmlBytes.length >> 8);
        packet[3] = (byte) xmlBytes.length;
        System.arraycopy(xmlBytes, 0, packet, 4, xmlBytes.length);

        Logger.d(TAG, "Sending lockdownd request: " + request.get("Request"));
        usbConnection.sendRaw(packet);

        // After usbmux connect, we're in raw TCP mode.
        // lockdownd response: 4-byte BE length prefix + XML plist
        byte[] response = usbConnection.receiveRaw();
        if (response == null || response.length < 4) {
            throw new IOException("Empty lockdownd response");
        }

        int plistLen = ByteBuffer.wrap(response, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();

        String responseXml;
        if (plistLen > 0 && plistLen <= response.length - 4) {
            responseXml = new String(response, 4, plistLen, StandardCharsets.UTF_8);
        } else {
            // Fallback: try treating entire response as plist
            responseXml = new String(response, StandardCharsets.UTF_8);
            if (!responseXml.contains("<?xml") && !responseXml.contains("<plist")) {
                throw new IOException("Invalid lockdownd response: no plist found (length field=" + plistLen + ")");
            }
        }

        Logger.d(TAG, "Received lockdownd response (" + responseXml.length() + " chars)");

        Map<String, Object> result = PlistParser.parse(responseXml);

        // Check for lockdownd errors and throw if critical
        String error = getStr(result, "Error");
        if (error != null && !error.isEmpty()) {
            throw new IOException("lockdownd error: " + error);
        }

        return result;
    }

    /**
     * Extract the "Value" dict from a lockdownd response, falling back to the response itself.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractValueDict(Map<String, Object> response) {
        if (response == null) return null;
        Object value = response.get("Value");
        if (value instanceof Map) return (Map<String, Object>) value;
        return response;
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : null;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Long) return ((Long) val).intValue();
        if (val instanceof Integer) return (Integer) val;
        return 0;
    }

    private static long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        return 0L;
    }
}
