package com.androidusb.iosreader.ssl;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Generates self-signed X.509 certificates for iOS lockdownd SSL pairing.
 *
 * Apple's lockdownd requires:
 * - A root CA certificate (self-signed)
 * - A host certificate (signed by root CA)
 * - A device certificate (device's public key signed by root CA)
 *
 * All certificates use RSA 2048 + SHA-256 signatures.
 */
public final class CertificateGenerator {

    private static final String RSA_ALGORITHM = "RSA";
    private static final int RSA_KEY_SIZE = 2048;

    // OIDs
    private static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";
    private static final String OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1";
    private static final String OID_COMMON_NAME = "2.5.4.3";
    private static final String OID_ORGANIZATION = "2.5.4.10";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_KEY_USAGE = "2.5.29.15";

    private CertificateGenerator() {}

    /**
     * Generate a new RSA 2048-bit key pair.
     */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        gen.initialize(RSA_KEY_SIZE);
        return gen.generateKeyPair();
    }

    /**
     * Build a self-signed root CA certificate.
     *
     * @param subjectCN Subject Common Name (e.g. "Android USB Root CA")
     * @param keyPair   RSA key pair for the root CA
     * @param validDays Number of days the cert is valid
     * @return DER-encoded X.509 certificate
     */
    public static byte[] buildRootCACert(String subjectCN, KeyPair keyPair, int validDays) throws Exception {
        return buildCert(subjectCN, subjectCN, keyPair.getPublic(), keyPair.getPrivate(),
                BigInteger.ONE, validDays, true);
    }

    /**
     * Build a host certificate signed by the root CA.
     *
     * @param hostCN      Host Common Name
     * @param rootCN      Root CA Common Name (issuer)
     * @param hostPublic  Host's public key
     * @param rootPrivate Root CA's private key (for signing)
     * @param serial      Certificate serial number
     * @param validDays   Number of days the cert is valid
     * @return DER-encoded X.509 certificate
     */
    public static byte[] buildHostCert(String hostCN, String rootCN,
                                        PublicKey hostPublic, PrivateKey rootPrivate,
                                        BigInteger serial, int validDays) throws Exception {
        return buildCert(hostCN, rootCN, hostPublic, rootPrivate, serial, validDays, false);
    }

    /**
     * Build a device certificate by signing the device's public key with the root CA.
     *
     * @param devicePublicKeyDER Device's DER-encoded public key (from lockdownd DevicePublicKey)
     * @param rootCN             Root CA Common Name (issuer)
     * @param rootPrivate        Root CA's private key (for signing)
     * @param serial             Certificate serial number
     * @param validDays          Number of days the cert is valid
     * @return DER-encoded X.509 certificate
     */
    public static byte[] buildDeviceCert(byte[] devicePublicKeyDER, String rootCN,
                                          PrivateKey rootPrivate,
                                          BigInteger serial, int validDays) throws Exception {
        PublicKey devicePublicKey = KeyFactory.getInstance(RSA_ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(devicePublicKeyDER));
        return buildCert("iOS Device", rootCN, devicePublicKey, rootPrivate, serial, validDays, false);
    }

    /**
     * Build a DER-encoded X.509v3 certificate.
     */
    private static byte[] buildCert(String subjectCN, String issuerCN,
                                     PublicKey subjectPublic, PrivateKey signerPrivate,
                                     BigInteger serial, int validDays,
                                     boolean isCA) throws Exception {
        // Validity dates
        Date notBefore = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(notBefore);
        cal.add(Calendar.DAY_OF_YEAR, validDays);
        Date notAfter = cal.getTime();

        // Build TBS (To-Be-Signed) certificate
        byte[] tbsCert = buildTBSCertificate(
                serial, issuerCN, subjectCN, notBefore, notAfter,
                subjectPublic.getEncoded(), isCA);

        // Sign the TBS certificate
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(signerPrivate);
        sig.update(tbsCert);
        byte[] signature = sig.sign();

        // Assemble full certificate: SEQUENCE { tbsCert, signatureAlgorithm, signatureValue }
        byte[] sigAlg = DEREncoder.sequence(
                DEREncoder.oid(OID_SHA256_WITH_RSA),
                DEREncoder.nullValue()
        );

        return DEREncoder.sequence(tbsCert, sigAlg, DEREncoder.bitString(signature));
    }

    /**
     * Build the TBSCertificate structure.
     */
    private static byte[] buildTBSCertificate(BigInteger serial, String issuerCN, String subjectCN,
                                               Date notBefore, Date notAfter,
                                               byte[] subjectPublicKeyInfo, boolean isCA) {
        // Version: [0] EXPLICIT INTEGER v3 (2)
        byte[] version = DEREncoder.contextTag(0, true, DEREncoder.integer(2));

        // Serial number
        byte[] serialNum = DEREncoder.integer(serial);

        // Signature algorithm
        byte[] signatureAlg = DEREncoder.sequence(
                DEREncoder.oid(OID_SHA256_WITH_RSA),
                DEREncoder.nullValue()
        );

        // Issuer
        byte[] issuer = buildName(issuerCN);

        // Validity
        byte[] validity = DEREncoder.sequence(
                DEREncoder.utcTime(formatUTCTime(notBefore)),
                DEREncoder.utcTime(formatUTCTime(notAfter))
        );

        // Subject
        byte[] subject = buildName(subjectCN);

        // SubjectPublicKeyInfo (already DER-encoded from PublicKey.getEncoded())
        byte[] spki = subjectPublicKeyInfo;

        // Extensions [3]
        byte[] extensions = buildExtensions(isCA);

        return DEREncoder.sequence(
                version, serialNum, signatureAlg, issuer, validity, subject, spki, extensions
        );
    }

    /**
     * Build an X.500 Name with CN and O attributes.
     */
    private static byte[] buildName(String cn) {
        byte[] cnAttr = DEREncoder.set(DEREncoder.sequence(
                DEREncoder.oid(OID_COMMON_NAME),
                DEREncoder.utf8String(cn)
        ));
        byte[] orgAttr = DEREncoder.set(DEREncoder.sequence(
                DEREncoder.oid(OID_ORGANIZATION),
                DEREncoder.utf8String("Android USB Reader")
        ));
        return DEREncoder.sequence(cnAttr, orgAttr);
    }

    /**
     * Build X.509v3 extensions.
     */
    private static byte[] buildExtensions(boolean isCA) {
        // BasicConstraints: CA:TRUE or CA:FALSE
        byte[] basicConstraints = DEREncoder.sequence(
                DEREncoder.oid(OID_BASIC_CONSTRAINTS),
                DEREncoder.booleanValue(true), // critical
                DEREncoder.octetString(DEREncoder.sequence(
                        DEREncoder.booleanValue(isCA)
                ))
        );

        // KeyUsage
        byte[] keyUsageBits;
        if (isCA) {
            // keyCertSign(5) + cRLSign(6) = 0x06 with 1 unused bit
            keyUsageBits = new byte[]{0x01, 0x06};
        } else {
            // digitalSignature(0) + keyEncipherment(2) = 0xA0 with 5 unused bits
            keyUsageBits = new byte[]{0x05, (byte) 0xA0};
        }
        byte[] keyUsage = DEREncoder.sequence(
                DEREncoder.oid(OID_KEY_USAGE),
                DEREncoder.booleanValue(true), // critical
                DEREncoder.octetString(DEREncoder.tlv(DEREncoder.TAG_BIT_STRING, keyUsageBits))
        );

        byte[] exts = DEREncoder.sequence(basicConstraints, keyUsage);
        return DEREncoder.contextTag(3, true, exts);
    }

    /**
     * Format a Date as UTCTime string "YYMMDDHHmmSSZ".
     */
    private static String formatUTCTime(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date) + "Z";
    }

    /**
     * Encode an RSA private key to PKCS#8 DER format.
     */
    public static byte[] privateKeyToDER(PrivateKey key) {
        return key.getEncoded(); // Already PKCS#8 DER on Android
    }

    /**
     * Decode an RSA private key from PKCS#8 DER format.
     */
    public static PrivateKey privateKeyFromDER(byte[] der) throws Exception {
        return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * Decode an RSA public key from X.509/DER format.
     */
    public static PublicKey publicKeyFromDER(byte[] der) throws Exception {
        return KeyFactory.getInstance(RSA_ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
    }
}
