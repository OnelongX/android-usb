package com.androidusb.iosreader.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppleModelMapperTest {

    @Test
    public void testKnownIPhoneModels() {
        assertEquals("iPhone 15 Pro Max", AppleModelMapper.getDisplayName("iPhone16,2"));
        assertEquals("iPhone 14", AppleModelMapper.getDisplayName("iPhone14,7"));
        assertEquals("iPhone SE (1st gen)", AppleModelMapper.getDisplayName("iPhone8,4"));
    }

    @Test
    public void testKnownIPadModels() {
        assertEquals("iPad (10th gen)", AppleModelMapper.getDisplayName("iPad13,18"));
        assertEquals("iPad mini (6th gen)", AppleModelMapper.getDisplayName("iPad14,1"));
    }

    @Test
    public void testUnknownModelFallback() {
        // Unknown model should parse family name
        String result = AppleModelMapper.getDisplayName("iPhone99,1");
        assertTrue(result.contains("iPhone"));
        assertTrue(result.contains("iPhone99,1"));
    }

    @Test
    public void testNullInput() {
        assertEquals("未知设备", AppleModelMapper.getDisplayName(null));
    }

    @Test
    public void testUnparseableInput() {
        assertEquals("SomeDevice", AppleModelMapper.getDisplayName("SomeDevice"));
    }

    @Test
    public void testIPhone16Models() {
        assertEquals("iPhone 16 Pro", AppleModelMapper.getDisplayName("iPhone17,1"));
        assertEquals("iPhone 16 Pro Max", AppleModelMapper.getDisplayName("iPhone17,2"));
        assertEquals("iPhone 16", AppleModelMapper.getDisplayName("iPhone17,3"));
        assertEquals("iPhone 16 Plus", AppleModelMapper.getDisplayName("iPhone17,4"));
    }
}
