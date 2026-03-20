package com.androidusb.iosreader.protocol;

import java.util.List;
import java.util.Map;

/**
 * Simple XML plist builder for constructing lockdownd request payloads.
 * Apple's lockdownd protocol uses XML property lists for communication.
 */
public class PlistBuilder {

    /**
     * Build a complete XML plist string from a key-value map.
     */
    public static String buildPlist(Map<String, Object> dict) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        sb.append("<plist version=\"1.0\">\n");
        sb.append("<dict>\n");

        for (Map.Entry<String, Object> entry : dict.entrySet()) {
            sb.append("\t<key>").append(escapeXml(entry.getKey())).append("</key>\n");
            appendValue(sb, entry.getValue());
        }

        sb.append("</dict>\n");
        sb.append("</plist>\n");
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value instanceof String) {
            sb.append("\t<string>").append(escapeXml((String) value)).append("</string>\n");
        } else if (value instanceof Integer) {
            sb.append("\t<integer>").append(value).append("</integer>\n");
        } else if (value instanceof Long) {
            sb.append("\t<integer>").append(value).append("</integer>\n");
        } else if (value instanceof Boolean) {
            sb.append("\t<").append(((Boolean) value) ? "true" : "false").append("/>\n");
        } else if (value instanceof byte[]) {
            sb.append("\t<data>").append(android.util.Base64.encodeToString(
                    (byte[]) value, android.util.Base64.NO_WRAP)).append("</data>\n");
        } else if (value instanceof Map) {
            sb.append("\t<dict>\n");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                sb.append("\t\t<key>").append(escapeXml(e.getKey())).append("</key>\n");
                appendValue(sb, e.getValue());
            }
            sb.append("\t</dict>\n");
        } else if (value instanceof List) {
            sb.append("\t<array>\n");
            for (Object item : (List<?>) value) {
                appendValue(sb, item);
            }
            sb.append("\t</array>\n");
        }
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
