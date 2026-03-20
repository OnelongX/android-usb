package com.androidusb.iosreader.ssl;

/**
 * Data class holding the iOS device pair record.
 * A pair record contains all certificates and keys needed for SSL communication
 * with an iOS device's lockdownd service.
 */
public class PairRecord {
    private byte[] rootCertificate;
    private byte[] hostCertificate;
    private byte[] deviceCertificate;
    private byte[] rootPrivateKey;
    private byte[] hostPrivateKey;
    private String hostID;
    private String systemBUID;

    public byte[] getRootCertificate() { return rootCertificate; }
    public void setRootCertificate(byte[] rootCertificate) { this.rootCertificate = rootCertificate; }

    public byte[] getHostCertificate() { return hostCertificate; }
    public void setHostCertificate(byte[] hostCertificate) { this.hostCertificate = hostCertificate; }

    public byte[] getDeviceCertificate() { return deviceCertificate; }
    public void setDeviceCertificate(byte[] deviceCertificate) { this.deviceCertificate = deviceCertificate; }

    public byte[] getRootPrivateKey() { return rootPrivateKey; }
    public void setRootPrivateKey(byte[] rootPrivateKey) { this.rootPrivateKey = rootPrivateKey; }

    public byte[] getHostPrivateKey() { return hostPrivateKey; }
    public void setHostPrivateKey(byte[] hostPrivateKey) { this.hostPrivateKey = hostPrivateKey; }

    public String getHostID() { return hostID; }
    public void setHostID(String hostID) { this.hostID = hostID; }

    public String getSystemBUID() { return systemBUID; }
    public void setSystemBUID(String systemBUID) { this.systemBUID = systemBUID; }
}
