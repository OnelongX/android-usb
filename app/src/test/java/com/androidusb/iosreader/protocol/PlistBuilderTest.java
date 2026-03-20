package com.androidusb.iosreader.protocol;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PlistBuilderTest {

    @Test
    public void testBuildSimplePlist() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("Label", "test-app");
        dict.put("Request", "QueryType");

        String xml = PlistBuilder.buildPlist(dict);

        assertTrue(xml.contains("<?xml version=\"1.0\""));
        assertTrue(xml.contains("<plist version=\"1.0\">"));
        assertTrue(xml.contains("<key>Label</key>"));
        assertTrue(xml.contains("<string>test-app</string>"));
        assertTrue(xml.contains("<key>Request</key>"));
        assertTrue(xml.contains("<string>QueryType</string>"));
        assertTrue(xml.contains("</dict>"));
        assertTrue(xml.contains("</plist>"));
    }

    @Test
    public void testBuildWithBoolean() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("Enabled", Boolean.TRUE);
        dict.put("Disabled", Boolean.FALSE);

        String xml = PlistBuilder.buildPlist(dict);
        assertTrue(xml.contains("<true/>"));
        assertTrue(xml.contains("<false/>"));
    }

    @Test
    public void testBuildWithInteger() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("Port", 62078);
        dict.put("LongVal", 9999999999L);

        String xml = PlistBuilder.buildPlist(dict);
        assertTrue(xml.contains("<integer>62078</integer>"));
        assertTrue(xml.contains("<integer>9999999999</integer>"));
    }

    @Test
    public void testXmlEscaping() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("Name", "Test <Device> & \"More\"");

        String xml = PlistBuilder.buildPlist(dict);
        assertTrue(xml.contains("&lt;Device&gt;"));
        assertTrue(xml.contains("&amp;"));
        assertTrue(xml.contains("&quot;More&quot;"));
    }

    @Test
    public void testBuildWithArray() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("Items", Arrays.asList("A", "B", "C"));

        String xml = PlistBuilder.buildPlist(dict);
        assertTrue(xml.contains("<array>"));
        assertTrue(xml.contains("<string>A</string>"));
        assertTrue(xml.contains("<string>B</string>"));
        assertTrue(xml.contains("<string>C</string>"));
        assertTrue(xml.contains("</array>"));
    }

    @Test
    public void testBuildWithNestedDict() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("Key", "Value");
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("Nested", inner);

        String xml = PlistBuilder.buildPlist(dict);
        assertTrue(xml.contains("<key>Nested</key>"));
        assertTrue(xml.contains("<key>Key</key>"));
        assertTrue(xml.contains("<string>Value</string>"));
    }

    @Test
    public void testEmptyDict() {
        Map<String, Object> dict = new LinkedHashMap<>();
        String xml = PlistBuilder.buildPlist(dict);
        assertTrue(xml.contains("<dict>"));
        assertTrue(xml.contains("</dict>"));
        assertFalse(xml.contains("<key>"));
    }
}
