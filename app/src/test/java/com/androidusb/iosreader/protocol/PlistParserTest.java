package com.androidusb.iosreader.protocol;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PlistParserTest {

    @Test
    public void testParseSimpleDict() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<plist version=\"1.0\"><dict>"
                + "<key>Name</key><string>iPhone</string>"
                + "<key>Version</key><string>17.0</string>"
                + "</dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        assertEquals("iPhone", result.get("Name"));
        assertEquals("17.0", result.get("Version"));
    }

    @Test
    public void testParseIntegerAndBoolean() throws IOException {
        String xml = "<plist version=\"1.0\"><dict>"
                + "<key>Port</key><integer>62078</integer>"
                + "<key>Enabled</key><true/>"
                + "<key>Locked</key><false/>"
                + "</dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        assertEquals(62078L, result.get("Port"));
        assertEquals(Boolean.TRUE, result.get("Enabled"));
        assertEquals(Boolean.FALSE, result.get("Locked"));
    }

    @Test
    public void testParseReal() throws IOException {
        String xml = "<plist version=\"1.0\"><dict>"
                + "<key>Ratio</key><real>3.14</real>"
                + "</dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        assertEquals(3.14, (Double) result.get("Ratio"), 0.001);
    }

    @Test
    public void testParseNestedDict() throws IOException {
        String xml = "<plist version=\"1.0\"><dict>"
                + "<key>Value</key><dict>"
                + "<key>DeviceName</key><string>Test</string>"
                + "</dict></dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.get("Value");
        assertNotNull(value);
        assertEquals("Test", value.get("DeviceName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testParseArray() throws IOException {
        String xml = "<plist version=\"1.0\"><dict>"
                + "<key>Items</key><array>"
                + "<string>A</string><string>B</string><string>C</string>"
                + "</array></dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        List<Object> items = (List<Object>) result.get("Items");
        assertNotNull(items);
        assertEquals(3, items.size());
        assertEquals("A", items.get(0));
        assertEquals("C", items.get(2));
    }

    @Test
    public void testParseEmptyDict() throws IOException {
        String xml = "<plist version=\"1.0\"><dict></dict></plist>";
        Map<String, Object> result = PlistParser.parse(xml);
        assertTrue(result.isEmpty());
    }

    @Test(expected = IOException.class)
    public void testParseInvalidXml() throws IOException {
        PlistParser.parse("not xml at all");
    }

    @Test
    public void testParseLockdowndResponse() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n<dict>\n"
                + "<key>Request</key><string>QueryType</string>\n"
                + "<key>Result</key><string>Success</string>\n"
                + "<key>Type</key><string>com.apple.mobile.lockdown</string>\n"
                + "</dict>\n</plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        assertEquals("QueryType", result.get("Request"));
        assertEquals("Success", result.get("Result"));
        assertEquals("com.apple.mobile.lockdown", result.get("Type"));
    }

    @Test
    public void testParseLargeInteger() throws IOException {
        String xml = "<plist version=\"1.0\"><dict>"
                + "<key>Capacity</key><integer>274877906944</integer>"
                + "</dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        assertEquals(274877906944L, result.get("Capacity"));
    }

    @Test
    public void testParseMalformedInteger() throws IOException {
        String xml = "<plist version=\"1.0\"><dict>"
                + "<key>Bad</key><integer>not_a_number</integer>"
                + "</dict></plist>";

        Map<String, Object> result = PlistParser.parse(xml);
        assertEquals(0L, result.get("Bad"));
    }
}
