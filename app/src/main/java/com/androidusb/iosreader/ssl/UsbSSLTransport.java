package com.androidusb.iosreader.ssl;

import com.androidusb.iosreader.usb.UsbMuxConnection;
import com.androidusb.iosreader.util.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Wraps a UsbMuxConnection with TLS encryption using SSLEngine.
 *
 * After lockdownd StartSession confirms SSL should be used, this class:
 * 1. Initializes an SSLEngine in client mode using the pair record's certs/keys
 * 2. Performs the TLS handshake by exchanging SSL records over USB bulk transfers
 * 3. Provides send/receive methods that encrypt/decrypt application data transparently
 *
 * This bridges Android's SSLEngine (which operates on ByteBuffers) with the USB
 * bulk transfer API (which operates on byte arrays).
 */
public class UsbSSLTransport {
    private static final String TAG = "UsbSSLTransport";
    private static final int MAX_UNWRAP_RETRIES = 10;

    private final UsbMuxConnection usbConnection;
    private SSLEngine sslEngine;

    // SSLEngine buffers
    private ByteBuffer appOutBuffer;    // plaintext data to encrypt
    private ByteBuffer netOutBuffer;    // encrypted data to send over USB
    private ByteBuffer netInBuffer;     // encrypted data received from USB
    private ByteBuffer appInBuffer;     // decrypted plaintext data

    private boolean sslEstablished = false;

    public UsbSSLTransport(UsbMuxConnection usbConnection) {
        this.usbConnection = usbConnection;
    }

    /**
     * Perform TLS handshake over the USB connection.
     *
     * @param pairRecord The pair record containing certificates and private key
     */
    public void startSSL(PairRecord pairRecord) throws IOException {
        try {
            Logger.i(TAG, "Starting SSL handshake");

            // Build key store with host certificate + private key
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            X509Certificate hostCert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pairRecord.getHostCertificate()));
            X509Certificate rootCert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pairRecord.getRootCertificate()));

            PrivateKey hostPrivateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pairRecord.getHostPrivateKey()));

            keyStore.setKeyEntry("host", hostPrivateKey, new char[0],
                    new Certificate[]{hostCert, rootCert});

            // Initialize SSLContext
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, new char[0]);

            // Trust the device certificate (verified via pairing handshake, not PKI).
            // This is safe because trust was established during the on-device "Trust" dialog.
            TrustManager[] pairingTrust = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), pairingTrust, null);

            // Create SSLEngine in client mode
            sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(true);

            allocateBuffers();

            // Begin handshake
            sslEngine.beginHandshake();
            performHandshake();

            // Re-allocate buffers after handshake in case session params changed
            allocateBuffers();

            sslEstablished = true;
            Logger.i(TAG, "SSL handshake completed. Protocol: "
                    + sslEngine.getSession().getProtocol()
                    + " Cipher: " + sslEngine.getSession().getCipherSuite());

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SSL initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Allocate or re-allocate SSLEngine buffers based on current session parameters.
     */
    private void allocateBuffers() {
        SSLSession session = sslEngine.getSession();
        int appBufSize = session.getApplicationBufferSize();
        int netBufSize = session.getPacketBufferSize();

        appOutBuffer = ByteBuffer.allocate(appBufSize);
        netOutBuffer = ByteBuffer.allocate(netBufSize);
        netInBuffer = ByteBuffer.allocate(netBufSize);
        appInBuffer = ByteBuffer.allocate(appBufSize);
    }

    /**
     * Perform the SSL/TLS handshake by exchanging records over USB.
     */
    private void performHandshake() throws IOException {
        SSLEngineResult.HandshakeStatus hsStatus = sslEngine.getHandshakeStatus();

        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED
                && hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            switch (hsStatus) {
                case NEED_WRAP:
                    hsStatus = doWrap();
                    break;
                case NEED_UNWRAP:
                    hsStatus = doUnwrap();
                    break;
                case NEED_TASK:
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    hsStatus = sslEngine.getHandshakeStatus();
                    break;
                default:
                    throw new IOException("Unexpected handshake status: " + hsStatus);
            }
        }
    }

    /**
     * Wrap (encrypt) outgoing data and send over USB.
     */
    private SSLEngineResult.HandshakeStatus doWrap() throws IOException {
        appOutBuffer.clear();
        appOutBuffer.flip(); // empty input for handshake wraps

        netOutBuffer.clear();
        SSLEngineResult result = sslEngine.wrap(appOutBuffer, netOutBuffer);

        switch (result.getStatus()) {
            case OK:
                netOutBuffer.flip();
                if (netOutBuffer.hasRemaining()) {
                    byte[] data = new byte[netOutBuffer.remaining()];
                    netOutBuffer.get(data);
                    sendRawSSL(data);
                }
                break;
            case BUFFER_OVERFLOW:
                netOutBuffer = enlargeBuffer(netOutBuffer,
                        sslEngine.getSession().getPacketBufferSize());
                return doWrap();
            default:
                throw new IOException("SSL wrap failed: " + result.getStatus());
        }

        return result.getHandshakeStatus();
    }

    /**
     * Receive data from USB and unwrap (decrypt).
     * Uses iterative approach to avoid recursive stack overflow and buffer state corruption.
     */
    private SSLEngineResult.HandshakeStatus doUnwrap() throws IOException {
        for (int retries = 0; retries < MAX_UNWRAP_RETRIES; retries++) {
            // Read encrypted data from USB if buffer has no unprocessed data
            if (netInBuffer.position() == 0) {
                byte[] raw = receiveRawSSL();
                netInBuffer.put(raw);
            }
            netInBuffer.flip();

            appInBuffer.clear();
            SSLEngineResult result = sslEngine.unwrap(netInBuffer, appInBuffer);
            netInBuffer.compact(); // preserve any leftover data for next unwrap

            switch (result.getStatus()) {
                case OK:
                    return result.getHandshakeStatus();

                case BUFFER_UNDERFLOW:
                    // Need more network data — read from USB and loop back
                    byte[] moreData = receiveRawSSL();
                    if (netInBuffer.remaining() < moreData.length) {
                        ByteBuffer expanded = ByteBuffer.allocate(
                                netInBuffer.capacity() + moreData.length);
                        netInBuffer.flip();
                        expanded.put(netInBuffer);
                        netInBuffer = expanded;
                    }
                    netInBuffer.put(moreData);
                    // Loop back — netInBuffer.position() > 0, so we skip the initial read
                    continue;

                case BUFFER_OVERFLOW:
                    appInBuffer = enlargeBuffer(appInBuffer,
                            sslEngine.getSession().getApplicationBufferSize());
                    // netInBuffer was compacted, so we still have data to retry
                    continue;

                default:
                    throw new IOException("SSL unwrap failed: " + result.getStatus());
            }
        }

        throw new IOException("SSL unwrap: too many retries");
    }

    /**
     * Send encrypted lockdownd message.
     */
    public void send(byte[] plaintext) throws IOException {
        if (!sslEstablished) {
            throw new IOException("SSL not established");
        }

        appOutBuffer.clear();
        if (plaintext.length > appOutBuffer.capacity()) {
            appOutBuffer = ByteBuffer.allocate(plaintext.length);
        }
        appOutBuffer.put(plaintext);
        appOutBuffer.flip();

        while (appOutBuffer.hasRemaining()) {
            netOutBuffer.clear();
            SSLEngineResult result = sslEngine.wrap(appOutBuffer, netOutBuffer);

            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOutBuffer = enlargeBuffer(netOutBuffer,
                        sslEngine.getSession().getPacketBufferSize());
                continue;
            }

            if (result.getStatus() != SSLEngineResult.Status.OK) {
                throw new IOException("SSL wrap failed during send: " + result.getStatus());
            }

            netOutBuffer.flip();
            if (netOutBuffer.hasRemaining()) {
                byte[] data = new byte[netOutBuffer.remaining()];
                netOutBuffer.get(data);
                sendRawSSL(data);
            }
        }
    }

    /**
     * Receive and decrypt a lockdownd message.
     *
     * @return Decrypted plaintext bytes
     */
    public byte[] receive() throws IOException {
        if (!sslEstablished) {
            throw new IOException("SSL not established");
        }

        // Read encrypted data from USB
        byte[] raw = receiveRawSSL();
        netInBuffer.clear();
        netInBuffer.put(raw);
        netInBuffer.flip();

        appInBuffer.clear();

        for (int retries = 0; retries < MAX_UNWRAP_RETRIES && netInBuffer.hasRemaining(); retries++) {
            SSLEngineResult result = sslEngine.unwrap(netInBuffer, appInBuffer);

            switch (result.getStatus()) {
                case OK:
                    // Handle delegated tasks from renegotiation
                    if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        Runnable task;
                        while ((task = sslEngine.getDelegatedTask()) != null) {
                            task.run();
                        }
                    }
                    // Done — exit the loop
                    appInBuffer.flip();
                    byte[] decrypted = new byte[appInBuffer.remaining()];
                    appInBuffer.get(decrypted);
                    return decrypted;

                case BUFFER_UNDERFLOW:
                    netInBuffer.compact();
                    byte[] more = receiveRawSSL();
                    if (netInBuffer.remaining() < more.length) {
                        ByteBuffer expanded = ByteBuffer.allocate(
                                netInBuffer.capacity() + more.length);
                        netInBuffer.flip();
                        expanded.put(netInBuffer);
                        netInBuffer = expanded;
                    }
                    netInBuffer.put(more);
                    netInBuffer.flip();
                    continue;

                case BUFFER_OVERFLOW:
                    appInBuffer = enlargeBuffer(appInBuffer,
                            sslEngine.getSession().getApplicationBufferSize());
                    continue;

                case CLOSED:
                    throw new IOException("SSL connection closed by peer");
            }
        }

        throw new IOException("SSL receive: failed to decrypt data");
    }

    /**
     * Check if SSL session is established.
     */
    public boolean isEstablished() {
        return sslEstablished;
    }

    /**
     * Close the SSL session gracefully.
     */
    public void close() {
        if (sslEngine != null && sslEstablished) {
            try {
                sslEngine.closeOutbound();
                netOutBuffer.clear();
                appOutBuffer.clear();
                appOutBuffer.flip();
                sslEngine.wrap(appOutBuffer, netOutBuffer);
                netOutBuffer.flip();
                if (netOutBuffer.hasRemaining()) {
                    byte[] closeNotify = new byte[netOutBuffer.remaining()];
                    netOutBuffer.get(closeNotify);
                    sendRawSSL(closeNotify);
                }
            } catch (Exception e) {
                Logger.w(TAG, "Error during SSL close: " + e.getMessage());
            }
            sslEstablished = false;
        }
        sslEngine = null;
    }

    private void sendRawSSL(byte[] data) throws IOException {
        usbConnection.sendRaw(data);
    }

    private byte[] receiveRawSSL() throws IOException {
        return usbConnection.receiveRawDirect();
    }

    /**
     * Enlarge a ByteBuffer while preserving unread data.
     */
    private static ByteBuffer enlargeBuffer(ByteBuffer buffer, int newCapacity) {
        if (newCapacity <= buffer.capacity()) {
            newCapacity = buffer.capacity() * 2;
        }
        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }
}
