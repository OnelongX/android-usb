package com.androidusb.iosreader.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Apple product type identifiers to human-readable device names.
 */
public class AppleModelMapper {

    private static final Map<String, String> MODEL_MAP = new HashMap<>();

    static {
        // iPhone
        MODEL_MAP.put("iPhone1,1", "iPhone");
        MODEL_MAP.put("iPhone1,2", "iPhone 3G");
        MODEL_MAP.put("iPhone2,1", "iPhone 3GS");
        MODEL_MAP.put("iPhone3,1", "iPhone 4");
        MODEL_MAP.put("iPhone3,2", "iPhone 4");
        MODEL_MAP.put("iPhone3,3", "iPhone 4 (CDMA)");
        MODEL_MAP.put("iPhone4,1", "iPhone 4S");
        MODEL_MAP.put("iPhone5,1", "iPhone 5");
        MODEL_MAP.put("iPhone5,2", "iPhone 5");
        MODEL_MAP.put("iPhone5,3", "iPhone 5c");
        MODEL_MAP.put("iPhone5,4", "iPhone 5c");
        MODEL_MAP.put("iPhone6,1", "iPhone 5s");
        MODEL_MAP.put("iPhone6,2", "iPhone 5s");
        MODEL_MAP.put("iPhone7,1", "iPhone 6 Plus");
        MODEL_MAP.put("iPhone7,2", "iPhone 6");
        MODEL_MAP.put("iPhone8,1", "iPhone 6s");
        MODEL_MAP.put("iPhone8,2", "iPhone 6s Plus");
        MODEL_MAP.put("iPhone8,4", "iPhone SE (1st gen)");
        MODEL_MAP.put("iPhone9,1", "iPhone 7");
        MODEL_MAP.put("iPhone9,2", "iPhone 7 Plus");
        MODEL_MAP.put("iPhone9,3", "iPhone 7");
        MODEL_MAP.put("iPhone9,4", "iPhone 7 Plus");
        MODEL_MAP.put("iPhone10,1", "iPhone 8");
        MODEL_MAP.put("iPhone10,2", "iPhone 8 Plus");
        MODEL_MAP.put("iPhone10,3", "iPhone X");
        MODEL_MAP.put("iPhone10,4", "iPhone 8");
        MODEL_MAP.put("iPhone10,5", "iPhone 8 Plus");
        MODEL_MAP.put("iPhone10,6", "iPhone X");
        MODEL_MAP.put("iPhone11,2", "iPhone XS");
        MODEL_MAP.put("iPhone11,4", "iPhone XS Max");
        MODEL_MAP.put("iPhone11,6", "iPhone XS Max");
        MODEL_MAP.put("iPhone11,8", "iPhone XR");
        MODEL_MAP.put("iPhone12,1", "iPhone 11");
        MODEL_MAP.put("iPhone12,3", "iPhone 11 Pro");
        MODEL_MAP.put("iPhone12,5", "iPhone 11 Pro Max");
        MODEL_MAP.put("iPhone12,8", "iPhone SE (2nd gen)");
        MODEL_MAP.put("iPhone13,1", "iPhone 12 mini");
        MODEL_MAP.put("iPhone13,2", "iPhone 12");
        MODEL_MAP.put("iPhone13,3", "iPhone 12 Pro");
        MODEL_MAP.put("iPhone13,4", "iPhone 12 Pro Max");
        MODEL_MAP.put("iPhone14,2", "iPhone 13 Pro");
        MODEL_MAP.put("iPhone14,3", "iPhone 13 Pro Max");
        MODEL_MAP.put("iPhone14,4", "iPhone 13 mini");
        MODEL_MAP.put("iPhone14,5", "iPhone 13");
        MODEL_MAP.put("iPhone14,6", "iPhone SE (3rd gen)");
        MODEL_MAP.put("iPhone14,7", "iPhone 14");
        MODEL_MAP.put("iPhone14,8", "iPhone 14 Plus");
        MODEL_MAP.put("iPhone15,2", "iPhone 14 Pro");
        MODEL_MAP.put("iPhone15,3", "iPhone 14 Pro Max");
        MODEL_MAP.put("iPhone15,4", "iPhone 15");
        MODEL_MAP.put("iPhone15,5", "iPhone 15 Plus");
        MODEL_MAP.put("iPhone16,1", "iPhone 15 Pro");
        MODEL_MAP.put("iPhone16,2", "iPhone 15 Pro Max");
        MODEL_MAP.put("iPhone17,1", "iPhone 16 Pro");
        MODEL_MAP.put("iPhone17,2", "iPhone 16 Pro Max");
        MODEL_MAP.put("iPhone17,3", "iPhone 16");
        MODEL_MAP.put("iPhone17,4", "iPhone 16 Plus");

        // iPad
        MODEL_MAP.put("iPad1,1", "iPad");
        MODEL_MAP.put("iPad2,1", "iPad 2");
        MODEL_MAP.put("iPad3,1", "iPad (3rd gen)");
        MODEL_MAP.put("iPad6,11", "iPad (5th gen)");
        MODEL_MAP.put("iPad7,5", "iPad (6th gen)");
        MODEL_MAP.put("iPad7,11", "iPad (7th gen)");
        MODEL_MAP.put("iPad11,6", "iPad (8th gen)");
        MODEL_MAP.put("iPad12,1", "iPad (9th gen)");
        MODEL_MAP.put("iPad13,18", "iPad (10th gen)");
        MODEL_MAP.put("iPad8,1", "iPad Pro 11-inch");
        MODEL_MAP.put("iPad8,9", "iPad Pro 11-inch (2nd gen)");
        MODEL_MAP.put("iPad13,4", "iPad Pro 11-inch (3rd gen)");
        MODEL_MAP.put("iPad14,3", "iPad Pro 11-inch (4th gen)");
        MODEL_MAP.put("iPad11,1", "iPad mini (5th gen)");
        MODEL_MAP.put("iPad14,1", "iPad mini (6th gen)");
        MODEL_MAP.put("iPad13,1", "iPad Air (4th gen)");
        MODEL_MAP.put("iPad13,16", "iPad Air (5th gen)");

        // iPod touch
        MODEL_MAP.put("iPod9,1", "iPod touch (7th gen)");
    }

    public static String getDisplayName(String productType) {
        if (productType == null) return "未知设备";
        String name = MODEL_MAP.get(productType);
        if (name != null) return name;

        // Fallback: parse "iPhone15,3" → "iPhone (15,3)"
        int commaIdx = productType.indexOf(',');
        if (commaIdx > 0) {
            String family = productType.substring(0, commaIdx).replaceAll("\\d+$", "");
            return family + " (" + productType + ")";
        }
        return productType;
    }
}
