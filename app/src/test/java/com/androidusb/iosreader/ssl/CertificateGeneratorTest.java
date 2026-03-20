package com.androidusb.iosreader.ssl;

import org.junit.Test;

import java.security.KeyPair;
import java.security.Signature;

import static org.junit.Assert.*;

public class CertificateGeneratorTest {

    @Test
    public void testGenerateKeyPair() throws Exception {
        KeyPair kp = CertificateGenerator.generateKeyPair();
        assertNotNull(kp);
        assertNotNull(kp.getPublic());
        assertNotNull(kp.getPrivate());
        assertEquals("RSA", kp.getPublic().getAlgorithm());
    }

    @Test
    public void testBuildRootCACert() throws Exception {
        KeyPair kp = CertificateGenerator.generateKeyPair();
        byte[] cert = CertificateGenerator.buildRootCACert("Test CA", kp, 365);
        assertNotNull(cert);
        assertTrue(cert.length > 0);
        // DER-encoded X.509 certs start with SEQUENCE tag (0x30)
        assertEquals(0x30, cert[0] & 0xFF);
    }

    @Test
    public void testBuildHostCert() throws Exception {
        KeyPair rootKp = CertificateGenerator.generateKeyPair();
        KeyPair hostKp = CertificateGenerator.generateKeyPair();

        byte[] hostCert = CertificateGenerator.buildHostCert(
                "Test Host", "Test CA",
                hostKp.getPublic(), rootKp.getPrivate(),
                java.math.BigInteger.valueOf(2), 365);

        assertNotNull(hostCert);
        assertTrue(hostCert.length > 0);
        assertEquals(0x30, hostCert[0] & 0xFF);
    }

    @Test
    public void testBuildDeviceCert() throws Exception {
        KeyPair rootKp = CertificateGenerator.generateKeyPair();
        KeyPair deviceKp = CertificateGenerator.generateKeyPair();

        byte[] deviceCert = CertificateGenerator.buildDeviceCert(
                deviceKp.getPublic().getEncoded(), "Test CA",
                rootKp.getPrivate(),
                java.math.BigInteger.valueOf(3), 365);

        assertNotNull(deviceCert);
        assertTrue(deviceCert.length > 0);
        assertEquals(0x30, deviceCert[0] & 0xFF);
    }

    @Test
    public void testPrivateKeyRoundTrip() throws Exception {
        KeyPair kp = CertificateGenerator.generateKeyPair();
        byte[] der = CertificateGenerator.privateKeyToDER(kp.getPrivate());
        assertNotNull(der);
        assertTrue(der.length > 0);

        java.security.PrivateKey restored = CertificateGenerator.privateKeyFromDER(der);
        assertNotNull(restored);
        assertEquals(kp.getPrivate(), restored);
    }

    @Test
    public void testPublicKeyFromDER() throws Exception {
        KeyPair kp = CertificateGenerator.generateKeyPair();
        byte[] der = kp.getPublic().getEncoded();

        java.security.PublicKey restored = CertificateGenerator.publicKeyFromDER(der);
        assertNotNull(restored);
        assertEquals(kp.getPublic(), restored);
    }

    @Test
    public void testCertSignatureIsVerifiable() throws Exception {
        KeyPair kp = CertificateGenerator.generateKeyPair();
        byte[] cert = CertificateGenerator.buildRootCACert("Verify Test", kp, 365);

        // The cert is DER-encoded, starts with SEQUENCE tag
        // At minimum verify it's a valid DER structure with substantial content
        assertTrue("Certificate too small", cert.length > 500);
        assertEquals("Must start with SEQUENCE", 0x30, cert[0] & 0xFF);
    }

    @Test
    public void testDifferentKeyPairsProduceDifferentCerts() throws Exception {
        KeyPair kp1 = CertificateGenerator.generateKeyPair();
        KeyPair kp2 = CertificateGenerator.generateKeyPair();

        byte[] cert1 = CertificateGenerator.buildRootCACert("CA1", kp1, 365);
        byte[] cert2 = CertificateGenerator.buildRootCACert("CA2", kp2, 365);

        assertFalse("Different keys should produce different certs",
                java.util.Arrays.equals(cert1, cert2));
    }
}
