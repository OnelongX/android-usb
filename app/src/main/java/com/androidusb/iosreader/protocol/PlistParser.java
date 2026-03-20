package com.androidusb.iosreader.protocol;

import com.androidusb.iosreader.util.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses Apple XML plist responses into Java Maps.
 */
public class PlistParser {
    private static final String TAG = "PlistParser";

    // Cache the factory to avoid re-creating it on every parse
    private static final DocumentBuilderFactory FACTORY;

    static {
        FACTORY = DocumentBuilderFactory.newInstance();
        try {
            // Apple plists use DOCTYPE declaration, so we cannot disallow it entirely.
            // Instead, disable all external entity resolution to prevent XXE.
            FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            FACTORY.setExpandEntityReferences(false);
            FACTORY.setXIncludeAware(false);
        } catch (ParserConfigurationException e) {
            Logger.e(TAG, "Failed to configure XML parser security features", e);
        }
    }

    /**
     * Parse an XML plist string into a Map.
     */
    public static Map<String, Object> parse(String xml) throws IOException {
        try {
            DocumentBuilder builder = FACTORY.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

            Element plist = doc.getDocumentElement();
            NodeList children = plist.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "dict".equals(node.getNodeName())) {
                    return parseDict((Element) node);
                }
            }

            return new HashMap<>();
        } catch (Exception e) {
            Logger.e(TAG, "Failed to parse plist", e);
            throw new IOException("Plist parse error: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> parseDict(Element dictElement) {
        Map<String, Object> result = new HashMap<>();
        NodeList children = dictElement.getChildNodes();

        String currentKey = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element elem = (Element) node;
            String tagName = elem.getTagName();

            if ("key".equals(tagName)) {
                currentKey = elem.getTextContent();
            } else if (currentKey != null) {
                result.put(currentKey, parseValue(elem));
                currentKey = null;
            }
        }

        return result;
    }

    private static Object parseValue(Element elem) {
        switch (elem.getTagName()) {
            case "string":
                return elem.getTextContent();
            case "integer":
                try {
                    return Long.parseLong(elem.getTextContent().trim());
                } catch (NumberFormatException e) {
                    Logger.w(TAG, "Malformed integer value: " + elem.getTextContent());
                    return 0L;
                }
            case "real":
                try {
                    return Double.parseDouble(elem.getTextContent().trim());
                } catch (NumberFormatException e) {
                    Logger.w(TAG, "Malformed real value: " + elem.getTextContent());
                    return 0.0;
                }
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            case "dict":
                return parseDict(elem);
            case "data":
                try {
                    return android.util.Base64.decode(elem.getTextContent().trim(),
                            android.util.Base64.DEFAULT);
                } catch (Exception e) {
                    Logger.w(TAG, "Failed to decode base64 data", e);
                    return new byte[0];
                }
            case "array":
                return parseArray(elem);
            default:
                return elem.getTextContent();
        }
    }

    private static List<Object> parseArray(Element arrayElement) {
        List<Object> result = new ArrayList<>();
        NodeList items = arrayElement.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            if (items.item(i).getNodeType() == Node.ELEMENT_NODE) {
                result.add(parseValue((Element) items.item(i)));
            }
        }
        return result;
    }
}
