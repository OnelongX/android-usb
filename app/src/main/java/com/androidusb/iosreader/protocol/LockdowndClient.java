package com.androidusb.iosreader.protocol;

import com.androidusb.iosreader.model.iOSDeviceInfo;
import com.androidusb.iosreader.ssl.PairRecord;
import com.androidusb.iosreader.ssl.UsbSSLTransport;
import com.androidusb.iosreader.usb.UsbMuxConnection;
import com.androidusb.iosreader.util.Logger;

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
 * Protocol flow:
 * 1. QueryType (plaintext) — validate lockdownd connection
 * 2. GetValue DevicePublicKey (plaintext) — retrieve device's public key
 * 3. Pair (plaintext) — send certificates for pairing
 * 4. StartSession (plaintext) — negotiate SSL session
 * 5. GetValue (encrypted via SSL) — full device info access
 *
 * Messages are XML plists prefixed with a 4-byte big-endian length header.
 * After StartSession with EnableSessionSSL, messages are wrapped in TLS.
 */
public class LockdowndClient {
    private static final String TAG = "LockdowndClient";
    private static final String CLIENT_LABEL = "android-usb-reader";

    private final UsbMuxConnection usbConnection;
    private UsbSSLTransport sslTransport;
    private boolean useSSL = false;

    public LockdowndClient(UsbMuxConnection usbConnection) {
        this.usbConnection = usbConnection;
    }

    // ==================== Plaintext phase ====================

    /**
     * Validate the connection by sending a QueryType request.
     * Must be called before pairing. Always plaintext.
     */
    public boolean queryType() throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", CLIENT_LABEL);
        request.put("Request", "QueryType");

        Map<String, Object> response = sendAndReceivePlain(request);
        Object type = response.get("Type");
        Logger.i(TAG, "QueryType response: " + type);
        return "com.apple.mobile.lockdown".equals(type);
    }

    /**
     * Get the device's public key for pairing.
     * Returns the DER-encoded RSA public key bytes.
     */
    public byte[] getDevicePublicKey() throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", CLIENT_LABEL);
        request.put("Request", "GetValue");
        request.put("Key", "DevicePublicKey");

        Map<String, Object> response = sendAndReceivePlain(request);
        Object value = response.get("Value");
        if (value instanceof byte[]) {
            Logger.i(TAG, "Retrieved DevicePublicKey (" + ((byte[]) value).length + " bytes)");
            return (byte[]) value;
        }
        throw new IOException("DevicePublicKey not found in response");
    }

    /**
     * Send a Pair request to the device.
     *
     * @param pairRecord The pair record containing generated certificates
     * @return true if pairing succeeded, false if the device needs user confirmation
     * @throws IOException on communication error or pairing rejection
     */
    public PairResult pair(PairRecord pairRecord) throws IOException {
        Map<String, Object> pairOptions = new LinkedHashMap<>();
        pairOptions.put("ExtendedPairingErrors", Boolean.TRUE);

        Map<String, Object> pairRecordDict = new LinkedHashMap<>();
        pairRecordDict.put("RootCertificate", pairRecord.getRootCertificate());
        pairRecordDict.put("HostCertificate", pairRecord.getHostCertificate());
        pairRecordDict.put("DeviceCertificate", pairRecord.getDeviceCertificate());
        pairRecordDict.put("HostID", pairRecord.getHostID());
        pairRecordDict.put("SystemBUID", pairRecord.getSystemBUID());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", CLIENT_LABEL);
        request.put("Request", "Pair");
        request.put("PairRecord", pairRecordDict);
        request.put("PairingOptions", pairOptions);

        Map<String, Object> response = sendAndReceivePlain(request);

        String error = getStr(response, "Error");
        if (error != null) {
            if ("PasswordProtected".equals(error)) {
                return PairResult.PASSWORD_PROTECTED;
            } else if ("UserDeniedPairing".equals(error)) {
                return PairResult.USER_DENIED;
            } else if ("PairingDialogResponsePending".equals(error)) {
                return PairResult.WAITING_FOR_USER;
            }
            throw new IOException("Pairing failed: " + error);
        }

        // Check if there's an EscrowBag in the response (successful pairing)
        Object escrowBag = response.get("EscrowBag");
        Logger.i(TAG, "Pairing succeeded" + (escrowBag != null ? " (with EscrowBag)" : ""));
        return PairResult.SUCCESS;
    }

    /**
     * Validate an existing pair record.
     */
    public boolean validatePair(PairRecord pairRecord) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", CLIENT_LABEL);
        request.put("Request", "ValidatePair");
        request.put("PairRecord", buildMinimalPairDict(pairRecord));

        Map<String, Object> response = sendAndReceivePlain(request);
        String error = getStr(response, "Error");
        if (error != null) {
            Logger.w(TAG, "ValidatePair error: " + error);
            return false;
        }
        return true;
    }

    // ==================== SSL session phase ====================

    /**
     * Start an SSL session with the device.
     * After this call succeeds, all subsequent communication is encrypted.
     *
     * @param pairRecord The pair record for this device
     * @return true if SSL session started successfully
     */
    public boolean startSession(PairRecord pairRecord) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Label", CLIENT_LABEL);
        request.put("Request", "StartSession");
        request.put("HostID", pairRecord.getHostID());
        request.put("SystemBUID", pairRecord.getSystemBUID());

        Map<String, Object> response = sendAndReceivePlain(request);

        String error = getStr(response, "Error");
        if (error != null) {
            throw new IOException("StartSession failed: " + error);
        }

        Object enableSSL = response.get("EnableSessionSSL");
        if (Boolean.TRUE.equals(enableSSL)) {
            Logger.i(TAG, "Device requests SSL. Starting TLS handshake...");

            sslTransport = new UsbSSLTransport(usbConnection);
            sslTransport.startSSL(pairRecord);
            useSSL = true;

            Logger.i(TAG, "SSL session established");
        } else {
            Logger.i(TAG, "Session started without SSL");
        }

        String sessionID = getStr(response, "SessionID");
        Logger.i(TAG, "Session started. ID: " + sessionID + " SSL: " + useSSL);
        return true;
    }

    // ==================== Device info (may use SSL) ====================

    /**
     * Retrieve all device values from lockdownd.
     * If SSL session is active, communication is encrypted.
     */
    public iOSDeviceInfo getDeviceInfo() throws IOException {
        iOSDeviceInfo info = new iOSDeviceInfo();

        // Get all values
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

        // Get battery info
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
     * Send a GetValue request.
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
     * Check if this client has an active SSL session.
     */
    public boolean isSSLActive() {
        return useSSL && sslTransport != null && sslTransport.isEstablished();
    }

    /**
     * Close the SSL transport if active.
     */
    public void closeSSL() {
        if (sslTransport != null) {
            sslTransport.close();
            sslTransport = null;
            useSSL = false;
        }
    }

    // ==================== Internal transport ====================

    /**
     * Send and receive using current transport (SSL or plaintext).
     */
    private Map<String, Object> sendAndReceive(Map<String, Object> request) throws IOException {
        if (useSSL) {
            return sendAndReceiveSSL(request);
        }
        return sendAndReceivePlain(request);
    }

    /**
     * Send/receive over plaintext USB.
     */
    private Map<String, Object> sendAndReceivePlain(Map<String, Object> request) throws IOException {
        String xml = PlistBuilder.buildPlist(request);
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);

        // 4-byte big-endian length prefix + XML
        byte[] packet = new byte[4 + xmlBytes.length];
        packet[0] = (byte) (xmlBytes.length >> 24);
        packet[1] = (byte) (xmlBytes.length >> 16);
        packet[2] = (byte) (xmlBytes.length >> 8);
        packet[3] = (byte) xmlBytes.length;
        System.arraycopy(xmlBytes, 0, packet, 4, xmlBytes.length);

        Logger.d(TAG, "Sending lockdownd request: " + request.get("Request"));
        usbConnection.sendRaw(packet);

        // Read response
        byte[] response = usbConnection.receiveRaw();
        return parseLockdowndResponse(response);
    }

    /**
     * Send/receive over SSL-encrypted channel.
     */
    private Map<String, Object> sendAndReceiveSSL(Map<String, Object> request) throws IOException {
        String xml = PlistBuilder.buildPlist(request);
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);

        // SSL messages still use 4-byte BE length prefix inside the encrypted stream
        byte[] packet = new byte[4 + xmlBytes.length];
        packet[0] = (byte) (xmlBytes.length >> 24);
        packet[1] = (byte) (xmlBytes.length >> 16);
        packet[2] = (byte) (xmlBytes.length >> 8);
        packet[3] = (byte) xmlBytes.length;
        System.arraycopy(xmlBytes, 0, packet, 4, xmlBytes.length);

        Logger.d(TAG, "Sending SSL lockdownd request: " + request.get("Request"));
        sslTransport.send(packet);

        byte[] response = sslTransport.receive();
        return parseLockdowndResponse(response);
    }

    /**
     * Parse a lockdownd response (4-byte BE length + XML plist).
     */
    private Map<String, Object> parseLockdowndResponse(byte[] response) throws IOException {
        if (response == null || response.length < 4) {
            throw new IOException("Empty lockdownd response");
        }

        int plistLen = ByteBuffer.wrap(response, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();

        String responseXml;
        if (plistLen > 0 && plistLen <= response.length - 4) {
            responseXml = new String(response, 4, plistLen, StandardCharsets.UTF_8);
        } else {
            responseXml = new String(response, StandardCharsets.UTF_8);
            if (!responseXml.contains("<?xml") && !responseXml.contains("<plist")) {
                throw new IOException("Invalid lockdownd response: no plist found (length field=" + plistLen + ")");
            }
        }

        Logger.d(TAG, "Received lockdownd response (" + responseXml.length() + " chars)");

        Map<String, Object> result = PlistParser.parse(responseXml);

        String error = getStr(result, "Error");
        if (error != null && !error.isEmpty()) {
            throw new IOException("lockdownd error: " + error);
        }

        return result;
    }

    private static Map<String, Object> buildMinimalPairDict(PairRecord pairRecord) {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("HostID", pairRecord.getHostID());
        dict.put("SystemBUID", pairRecord.getSystemBUID());
        return dict;
    }

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

    /**
     * Result of a Pair request.
     */
    public enum PairResult {
        /** Pairing completed successfully. */
        SUCCESS,
        /** Device is password-protected; user must unlock it. */
        PASSWORD_PROTECTED,
        /** User denied the pairing dialog on the device. */
        USER_DENIED,
        /** Pairing dialog is shown on device; waiting for user to tap "Trust". */
        WAITING_FOR_USER
    }
}
