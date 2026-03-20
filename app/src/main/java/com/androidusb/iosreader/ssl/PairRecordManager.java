package com.androidusb.iosreader.ssl;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.androidusb.iosreader.util.Logger;

import java.math.BigInteger;
import java.security.KeyPair;
import java.util.UUID;

/**
 * Manages iOS device pair records: generation, storage, and retrieval.
 *
 * Pair records are persisted in SharedPreferences, keyed by device UDID.
 * Each record contains the certificates and private keys needed for SSL
 * communication with the paired iOS device.
 */
public class PairRecordManager {
    private static final String TAG = "PairRecordManager";
    private static final String PREFS_NAME = "ios_pair_records";
    private static final String ROOT_CN = "Android USB Root CA";
    private static final String HOST_CN = "Android USB Host";
    private static final int CERT_VALID_DAYS = 3650; // 10 years

    // SharedPreferences keys (per UDID)
    private static final String KEY_ROOT_CERT = "_root_cert";
    private static final String KEY_HOST_CERT = "_host_cert";
    private static final String KEY_DEVICE_CERT = "_device_cert";
    private static final String KEY_ROOT_KEY = "_root_key";
    private static final String KEY_HOST_KEY = "_host_key";
    private static final String KEY_HOST_ID = "_host_id";
    private static final String KEY_SYSTEM_BUID = "_system_buid";

    private final SharedPreferences prefs;
    private String systemBUID;

    public PairRecordManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.systemBUID = prefs.getString("system_buid", null);
        if (systemBUID == null) {
            systemBUID = UUID.randomUUID().toString().toUpperCase();
            prefs.edit().putString("system_buid", systemBUID).apply();
        }
    }

    /**
     * Check if a pair record exists for the given UDID.
     */
    public boolean hasPairRecord(String udid) {
        return prefs.contains(udid + KEY_HOST_ID);
    }

    /**
     * Load an existing pair record for the given UDID.
     */
    public PairRecord loadPairRecord(String udid) {
        if (!hasPairRecord(udid)) return null;

        PairRecord record = new PairRecord();
        record.setRootCertificate(loadBytes(udid + KEY_ROOT_CERT));
        record.setHostCertificate(loadBytes(udid + KEY_HOST_CERT));
        record.setDeviceCertificate(loadBytes(udid + KEY_DEVICE_CERT));
        record.setRootPrivateKey(loadBytes(udid + KEY_ROOT_KEY));
        record.setHostPrivateKey(loadBytes(udid + KEY_HOST_KEY));
        record.setHostID(prefs.getString(udid + KEY_HOST_ID, null));
        record.setSystemBUID(prefs.getString(udid + KEY_SYSTEM_BUID, systemBUID));
        return record;
    }

    /**
     * Generate a new pair record for pairing with an iOS device.
     *
     * @param udid              Device UDID
     * @param devicePublicKeyDER Device's DER-encoded public key (from lockdownd "DevicePublicKey")
     * @return A new PairRecord ready to be sent in a Pair request
     */
    public PairRecord generatePairRecord(String udid, byte[] devicePublicKeyDER) throws Exception {
        Logger.i(TAG, "Generating new pair record for UDID: " + udid);

        // Generate root CA key pair
        KeyPair rootKeyPair = CertificateGenerator.generateKeyPair();
        byte[] rootCert = CertificateGenerator.buildRootCACert(ROOT_CN, rootKeyPair, CERT_VALID_DAYS);

        // Generate host key pair
        KeyPair hostKeyPair = CertificateGenerator.generateKeyPair();
        byte[] hostCert = CertificateGenerator.buildHostCert(
                HOST_CN, ROOT_CN, hostKeyPair.getPublic(), rootKeyPair.getPrivate(),
                BigInteger.valueOf(2), CERT_VALID_DAYS);

        // Build device certificate
        byte[] deviceCert = CertificateGenerator.buildDeviceCert(
                devicePublicKeyDER, ROOT_CN, rootKeyPair.getPrivate(),
                BigInteger.valueOf(3), CERT_VALID_DAYS);

        String hostID = UUID.randomUUID().toString().toUpperCase();

        PairRecord record = new PairRecord();
        record.setRootCertificate(rootCert);
        record.setHostCertificate(hostCert);
        record.setDeviceCertificate(deviceCert);
        record.setRootPrivateKey(CertificateGenerator.privateKeyToDER(rootKeyPair.getPrivate()));
        record.setHostPrivateKey(CertificateGenerator.privateKeyToDER(hostKeyPair.getPrivate()));
        record.setHostID(hostID);
        record.setSystemBUID(systemBUID);

        // Persist
        savePairRecord(udid, record);

        Logger.i(TAG, "Pair record generated and saved for UDID: " + udid);
        return record;
    }

    /**
     * Save a pair record to SharedPreferences.
     */
    public void savePairRecord(String udid, PairRecord record) {
        SharedPreferences.Editor editor = prefs.edit();
        saveBytes(editor, udid + KEY_ROOT_CERT, record.getRootCertificate());
        saveBytes(editor, udid + KEY_HOST_CERT, record.getHostCertificate());
        saveBytes(editor, udid + KEY_DEVICE_CERT, record.getDeviceCertificate());
        saveBytes(editor, udid + KEY_ROOT_KEY, record.getRootPrivateKey());
        saveBytes(editor, udid + KEY_HOST_KEY, record.getHostPrivateKey());
        editor.putString(udid + KEY_HOST_ID, record.getHostID());
        editor.putString(udid + KEY_SYSTEM_BUID, record.getSystemBUID());
        editor.apply();
    }

    /**
     * Delete a pair record.
     */
    public void deletePairRecord(String udid) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(udid + KEY_ROOT_CERT);
        editor.remove(udid + KEY_HOST_CERT);
        editor.remove(udid + KEY_DEVICE_CERT);
        editor.remove(udid + KEY_ROOT_KEY);
        editor.remove(udid + KEY_HOST_KEY);
        editor.remove(udid + KEY_HOST_ID);
        editor.remove(udid + KEY_SYSTEM_BUID);
        editor.apply();
    }

    public String getSystemBUID() {
        return systemBUID;
    }

    private void saveBytes(SharedPreferences.Editor editor, String key, byte[] data) {
        if (data != null) {
            editor.putString(key, Base64.encodeToString(data, Base64.NO_WRAP));
        }
    }

    private byte[] loadBytes(String key) {
        String encoded = prefs.getString(key, null);
        if (encoded == null) return null;
        return Base64.decode(encoded, Base64.NO_WRAP);
    }
}
