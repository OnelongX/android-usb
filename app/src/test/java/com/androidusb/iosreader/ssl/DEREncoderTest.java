package com.androidusb.iosreader.ssl;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.*;

public class DEREncoderTest {

    @Test
    public void testSequence() {
        byte[] inner = DEREncoder.integer(42);
        byte[] seq = DEREncoder.sequence(inner);
        // Should start with SEQUENCE tag (0x30)
        assertEquals(0x30, seq[0] & 0xFF);
    }

    @Test
    public void testInteger() {
        byte[] encoded = DEREncoder.integer(0);
        assertEquals(DEREncoder.TAG_INTEGER, encoded[0] & 0xFF);
        assertEquals(1, encoded[1]); // length
        assertEquals(0, encoded[2]); // value
    }

    @Test
    public void testIntegerBigPositive() {
        byte[] encoded = DEREncoder.integer(BigInteger.valueOf(256));
        assertEquals(DEREncoder.TAG_INTEGER, encoded[0] & 0xFF);
        assertEquals(2, encoded[1]); // length: 2 bytes for 256
    }

    @Test
    public void testBitString() {
        byte[] data = {0x01, 0x02, 0x03};
        byte[] encoded = DEREncoder.bitString(data);
        assertEquals(DEREncoder.TAG_BIT_STRING, encoded[0] & 0xFF);
        assertEquals(4, encoded[1]); // length: 1 (unused bits) + 3 (data)
        assertEquals(0, encoded[2]); // 0 unused bits
    }

    @Test
    public void testOctetString() {
        byte[] data = {(byte) 0xAA, (byte) 0xBB};
        byte[] encoded = DEREncoder.octetString(data);
        assertEquals(DEREncoder.TAG_OCTET_STRING, encoded[0] & 0xFF);
        assertEquals(2, encoded[1]);
    }

    @Test
    public void testOidSimple() {
        // OID 1.2.3 -> first byte: 1*40+2=42, second byte: 3
        byte[] encoded = DEREncoder.oid("1.2.3");
        assertEquals(DEREncoder.TAG_OID, encoded[0] & 0xFF);
        assertEquals(2, encoded[1]); // 2 bytes of OID content
        assertEquals(42, encoded[2]); // 1*40 + 2
        assertEquals(3, encoded[3]);
    }

    @Test
    public void testOidLargeComponent() {
        // OID with component > 127 requires base-128 encoding
        byte[] encoded = DEREncoder.oid("1.2.840");
        assertEquals(DEREncoder.TAG_OID, encoded[0] & 0xFF);
        // 840 in base-128: 0x86, 0x48
        assertTrue(encoded.length > 4);
    }

    @Test
    public void testUtf8String() {
        byte[] encoded = DEREncoder.utf8String("Test");
        assertEquals(DEREncoder.TAG_UTF8_STRING, encoded[0] & 0xFF);
        assertEquals(4, encoded[1]);
    }

    @Test
    public void testPrintableString() {
        byte[] encoded = DEREncoder.printableString("ABC");
        assertEquals(DEREncoder.TAG_PRINTABLE_STRING, encoded[0] & 0xFF);
        assertEquals(3, encoded[1]);
    }

    @Test
    public void testUtcTime() {
        String time = "260101120000Z";
        byte[] encoded = DEREncoder.utcTime(time);
        assertEquals(DEREncoder.TAG_UTC_TIME, encoded[0] & 0xFF);
        assertEquals(13, encoded[1]);
    }

    @Test
    public void testNullValue() {
        byte[] encoded = DEREncoder.nullValue();
        assertEquals(DEREncoder.TAG_NULL, encoded[0] & 0xFF);
        assertEquals(0, encoded[1]);
    }

    @Test
    public void testBooleanTrue() {
        byte[] encoded = DEREncoder.booleanValue(true);
        assertEquals(DEREncoder.TAG_BOOLEAN, encoded[0] & 0xFF);
        assertEquals(1, encoded[1]);
        assertEquals((byte) 0xFF, encoded[2]);
    }

    @Test
    public void testBooleanFalse() {
        byte[] encoded = DEREncoder.booleanValue(false);
        assertEquals(DEREncoder.TAG_BOOLEAN, encoded[0] & 0xFF);
        assertEquals(1, encoded[1]);
        assertEquals(0, encoded[2]);
    }

    @Test
    public void testContextTag() {
        byte[] content = DEREncoder.integer(1);
        byte[] encoded = DEREncoder.contextTag(0, true, content);
        // tag = 0x80 | 0x20 | 0 = 0xA0
        assertEquals((byte) 0xA0, encoded[0]);
    }

    @Test
    public void testConcat() {
        byte[] a = {1, 2};
        byte[] b = {3, 4, 5};
        byte[] result = DEREncoder.concat(a, b);
        assertEquals(5, result.length);
        assertEquals(1, result[0]);
        assertEquals(5, result[4]);
    }

    @Test
    public void testSet() {
        byte[] inner = DEREncoder.integer(1);
        byte[] set = DEREncoder.set(inner);
        assertEquals(DEREncoder.TAG_SET, set[0] & 0xFF);
    }

    @Test
    public void testLongLengthEncoding() {
        // Create data > 127 bytes to test multi-byte length
        byte[] data = new byte[200];
        byte[] encoded = DEREncoder.octetString(data);
        assertEquals(DEREncoder.TAG_OCTET_STRING, encoded[0] & 0xFF);
        // Length should be encoded as 0x81, 0xC8 (200)
        assertEquals((byte) 0x81, encoded[1]);
        assertEquals((byte) 200, encoded[2]);
    }
}
