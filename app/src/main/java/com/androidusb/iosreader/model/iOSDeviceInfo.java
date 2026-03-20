package com.androidusb.iosreader.model;

/**
 * Data class holding iOS device information retrieved via lockdownd.
 */
public class iOSDeviceInfo {
    private String deviceName;
    private String modelNumber;
    private String productType;
    private String productVersion;
    private String buildVersion;
    private String serialNumber;
    private String uniqueDeviceID;
    private String wifiAddress;
    private String bluetoothAddress;
    private String phoneNumber;
    private String internationalMobileEquipmentIdentity;
    private long totalDiskCapacity;
    private long availableDiskCapacity;
    private int batteryLevel;
    private boolean batteryCharging;
    private String cpuArchitecture;
    private String hardwareModel;

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getModelNumber() { return modelNumber; }
    public void setModelNumber(String modelNumber) { this.modelNumber = modelNumber; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public String getProductVersion() { return productVersion; }
    public void setProductVersion(String productVersion) { this.productVersion = productVersion; }

    public String getBuildVersion() { return buildVersion; }
    public void setBuildVersion(String buildVersion) { this.buildVersion = buildVersion; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getUniqueDeviceID() { return uniqueDeviceID; }
    public void setUniqueDeviceID(String uniqueDeviceID) { this.uniqueDeviceID = uniqueDeviceID; }

    public String getWifiAddress() { return wifiAddress; }
    public void setWifiAddress(String wifiAddress) { this.wifiAddress = wifiAddress; }

    public String getBluetoothAddress() { return bluetoothAddress; }
    public void setBluetoothAddress(String bluetoothAddress) { this.bluetoothAddress = bluetoothAddress; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getIMEI() { return internationalMobileEquipmentIdentity; }
    public void setIMEI(String imei) { this.internationalMobileEquipmentIdentity = imei; }

    public long getTotalDiskCapacity() { return totalDiskCapacity; }
    public void setTotalDiskCapacity(long totalDiskCapacity) { this.totalDiskCapacity = totalDiskCapacity; }

    public long getAvailableDiskCapacity() { return availableDiskCapacity; }
    public void setAvailableDiskCapacity(long availableDiskCapacity) { this.availableDiskCapacity = availableDiskCapacity; }

    public int getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(int batteryLevel) { this.batteryLevel = batteryLevel; }

    public boolean isBatteryCharging() { return batteryCharging; }
    public void setBatteryCharging(boolean batteryCharging) { this.batteryCharging = batteryCharging; }

    public String getCpuArchitecture() { return cpuArchitecture; }
    public void setCpuArchitecture(String cpuArchitecture) { this.cpuArchitecture = cpuArchitecture; }

    public String getHardwareModel() { return hardwareModel; }
    public void setHardwareModel(String hardwareModel) { this.hardwareModel = hardwareModel; }

    public String getFormattedStorage() {
        if (totalDiskCapacity <= 0) return "未知";
        double totalGB = totalDiskCapacity / (1024.0 * 1024.0 * 1024.0);
        double availGB = availableDiskCapacity / (1024.0 * 1024.0 * 1024.0);
        double usedGB = totalGB - availGB;
        return String.format("%.1f GB / %.1f GB (可用 %.1f GB)", usedGB, totalGB, availGB);
    }

    public String getDisplayModel() {
        if (productType == null) return modelNumber;
        return AppleModelMapper.getDisplayName(productType);
    }
}
