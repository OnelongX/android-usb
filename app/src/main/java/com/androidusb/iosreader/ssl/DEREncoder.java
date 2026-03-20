package com.androidusb.iosreader.ssl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * Minimal DER (Distinguished Encoding Rules) / ASN.1 encoder for
 * building X.509 certificates without external dependencies.
 *
 * Supports the subset of ASN.1 types needed for self-signed certs:
 * SEQUENCE, SET, INTEGER, BIT STRING, OCTET STRING, OID, UTF8String,
 * PrintableString, UTCTime, BOOLEAN, context-tagged constructs.
 */
public final class DEREncoder {

    // ASN.1 tag constants
    public static final int TAG_BOOLEAN = 0x01;
    public static final int TAG_INTEGER = 0x02;
    public static final int TAG_BIT_STRING = 0x03;
    public static final int TAG_OCTET_STRING = 0x04;
    public static final int TAG_NULL = 0x05;
    public static final int TAG_OID = 0x06;
    public static final int TAG_UTF8_STRING = 0x0C;
    public static final int TAG_PRINTABLE_STRING = 0x13;
    public static final int TAG_UTC_TIME = 0x17;
    public static final int TAG_SEQUENCE = 0x30;
    public static final int TAG_SET = 0x31;

    private DEREncoder() {}

    /**
     * Encode a TLV (Tag-Length-Value) triplet.
     */
    public static byte[] tlv(int tag, byte[] value) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(tag);
            writeLength(out, value.length);
            out.write(value);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("DER encoding failed", e);
        }
    }

    /**
     * Encode a SEQUENCE containing the concatenation of the given DER-encoded elements.
     */
    public static byte[] sequence(byte[]... elements) {
        return tlv(TAG_SEQUENCE, concat(elements));
    }

    /**
     * Encode a SET containing the concatenation of the given DER-encoded elements.
     */
    public static byte[] set(byte[]... elements) {
        return tlv(TAG_SET, concat(elements));
    }

    /**
     * Encode an ASN.1 INTEGER from a BigInteger.
     */
    public static byte[] integer(BigInteger value) {
        return tlv(TAG_INTEGER, value.toByteArray());
    }

    /**
     * Encode an ASN.1 INTEGER from a long.
     */
    public static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    /**
     * Encode a BIT STRING (with 0 unused bits).
     */
    public static byte[] bitString(byte[] data) {
        byte[] value = new byte[data.length + 1];
        value[0] = 0; // no unused bits
        System.arraycopy(data, 0, value, 1, data.length);
        return tlv(TAG_BIT_STRING, value);
    }

    /**
     * Encode an OCTET STRING.
     */
    public static byte[] octetString(byte[] data) {
        return tlv(TAG_OCTET_STRING, data);
    }

    /**
     * Encode an OID from its dotted-decimal string representation (e.g. "1.2.840.113549.1.1.11").
     */
    public static byte[] oid(String dottedString) {
        String[] parts = dottedString.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("OID must have at least 2 components");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        out.write(first * 40 + second);

        for (int i = 2; i < parts.length; i++) {
            long component = Long.parseLong(parts[i]);
            writeBase128(out, component);
        }

        return tlv(TAG_OID, out.toByteArray());
    }

    /**
     * Encode a UTF8String.
     */
    public static byte[] utf8String(String s) {
        return tlv(TAG_UTF8_STRING, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Encode a PrintableString.
     */
    public static byte[] printableString(String s) {
        return tlv(TAG_PRINTABLE_STRING, s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /**
     * Encode a UTCTime string (format: "YYMMDDHHmmSSZ").
     */
    public static byte[] utcTime(String timeStr) {
        return tlv(TAG_UTC_TIME, timeStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /**
     * Encode a NULL value.
     */
    public static byte[] nullValue() {
        return new byte[]{TAG_NULL, 0x00};
    }

    /**
     * Encode a BOOLEAN value.
     */
    public static byte[] booleanValue(boolean val) {
        return tlv(TAG_BOOLEAN, new byte[]{(byte) (val ? 0xFF : 0x00)});
    }

    /**
     * Encode a context-specific tagged construct (e.g. [0] EXPLICIT, [3] EXPLICIT).
     */
    public static byte[] contextTag(int tagNumber, boolean constructed, byte[] content) {
        int tag = 0x80 | tagNumber;
        if (constructed) tag |= 0x20;
        return tlv(tag, content);
    }

    /**
     * Write DER length encoding.
     */
    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        } else if (length < 0x1000000) {
            out.write(0x83);
            out.write(length >> 16);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x84);
            out.write(length >> 24);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    /**
     * Write a base-128 encoded OID component.
     */
    private static void writeBase128(ByteArrayOutputStream out, long value) {
        if (value < 128) {
            out.write((int) value);
            return;
        }
        // Determine number of bytes needed
        int numBytes = 0;
        long temp = value;
        while (temp > 0) {
            numBytes++;
            temp >>= 7;
        }
        for (int i = numBytes - 1; i >= 0; i--) {
            int b = (int) ((value >> (7 * i)) & 0x7F);
            if (i > 0) b |= 0x80;
            out.write(b);
        }
    }

    /**
     * Concatenate multiple byte arrays.
     */
    public static byte[] concat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] a : arrays) totalLen += a.length;
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }
}
