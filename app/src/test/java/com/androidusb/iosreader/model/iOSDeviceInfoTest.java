package com.androidusb.iosreader.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class iOSDeviceInfoTest {

    @Test
    public void testFormattedStorage() {
        iOSDeviceInfo info = new iOSDeviceInfo();
        long oneGB = 1024L * 1024L * 1024L;
        info.setTotalDiskCapacity(256 * oneGB);
        info.setAvailableDiskCapacity(100 * oneGB);

        String result = info.getFormattedStorage();
        assertNotNull(result);
        assertTrue(result.contains("156.0"));
        assertTrue(result.contains("256.0"));
        assertTrue(result.contains("100.0"));
        assertTrue(result.contains("GB"));
    }

    @Test
    public void testFormattedStorageZero() {
        iOSDeviceInfo info = new iOSDeviceInfo();
        assertNull(info.getFormattedStorage());
    }

    @Test
    public void testDisplayModelWithProductType() {
        iOSDeviceInfo info = new iOSDeviceInfo();
        info.setProductType("iPhone16,2");
        info.setModelNumber("A2849");
        assertEquals("iPhone 15 Pro Max", info.getDisplayModel());
    }

    @Test
    public void testDisplayModelFallsBackToModelNumber() {
        iOSDeviceInfo info = new iOSDeviceInfo();
        info.setModelNumber("A2849");
        assertEquals("A2849", info.getDisplayModel());
    }

    @Test
    public void testBatteryDefaults() {
        iOSDeviceInfo info = new iOSDeviceInfo();
        assertEquals(0, info.getBatteryLevel());
        assertFalse(info.isBatteryCharging());
    }

    @Test
    public void testSettersAndGetters() {
        iOSDeviceInfo info = new iOSDeviceInfo();
        info.setDeviceName("My iPhone");
        info.setSerialNumber("ABC123");
        info.setUniqueDeviceID("UDID-123");
        info.setWifiAddress("AA:BB:CC:DD:EE:FF");
        info.setBluetoothAddress("11:22:33:44:55:66");
        info.setPhoneNumber("+1234567890");
        info.setIMEI("123456789012345");
        info.setCpuArchitecture("arm64");
        info.setHardwareModel("D83AP");
        info.setBatteryLevel(85);
        info.setBatteryCharging(true);

        assertEquals("My iPhone", info.getDeviceName());
        assertEquals("ABC123", info.getSerialNumber());
        assertEquals("UDID-123", info.getUniqueDeviceID());
        assertEquals("AA:BB:CC:DD:EE:FF", info.getWifiAddress());
        assertEquals("11:22:33:44:55:66", info.getBluetoothAddress());
        assertEquals("+1234567890", info.getPhoneNumber());
        assertEquals("123456789012345", info.getIMEI());
        assertEquals("arm64", info.getCpuArchitecture());
        assertEquals("D83AP", info.getHardwareModel());
        assertEquals(85, info.getBatteryLevel());
        assertTrue(info.isBatteryCharging());
    }
}
