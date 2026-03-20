package com.androidusb.iosreader.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles low-level USB communication with iOS devices using the usbmuxd protocol.
 *
 * iOS devices expose a CDC-like interface for usbmux communication. This class
 * manages claiming the correct USB interface and performing bulk transfers to
 * establish a usbmux session, which is required before any higher-level protocol
 * (lockdownd) communication can take place.
 */
public class UsbMuxConnection {
    private static final String TAG = "UsbMuxConnection";

    private static final int APPLE_VENDOR_ID = 0x05AC;
    private static final int USB_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_SIZE = 256 * 1024; // 256 KB max response

    // usbmuxd protocol constants
    private static final int USBMUX_VERSION = 1;
    private static final int USBMUX_MSG_CONNECT = 2;
    private static final int USBMUX_MSG_RESULT = 1;

    // lockdownd listens on port 62078
    private static final int LOCKDOWND_PORT = 62078;

    // usbmux header: length(4) + version(4) + type(4) + tag(4) = 16 bytes
    private static final int USBMUX_HEADER_SIZE = 16;
    // connect body: port(4) + device_id(4) = 8 bytes
    private static final int USBMUX_CONNECT_BODY_SIZE = 8;

    private final UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private int currentTag = 1;

    // Reusable buffer for receiving data
    private byte[] receiveBuffer;

    public UsbMuxConnection(UsbManager usbManager) {
        this.usbManager = usbManager;
    }

    /**
     * Find and return the first connected Apple USB device.
     */
    public UsbDevice findAppleDevice() {
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == APPLE_VENDOR_ID) {
                Log.i(TAG, "Found Apple device: " + dev.getDeviceName()
                        + " (product=" + dev.getProductId() + ")");
                this.device = dev;
                return dev;
            }
        }
        return null;
    }

    /**
     * Open a USB connection and claim the appropriate interface.
     * iOS devices typically use interface class 255 (vendor-specific) with
     * subclass 254 and protocol 2 for the usbmux endpoint.
     */
    public boolean open() throws IOException {
        if (device == null) throw new IOException("No Apple device found");

        connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IOException("Cannot open USB device - permission denied?");
        }

        try {
            // Find the usbmux interface (class 0xFF, subclass 0xFE, protocol 2)
            usbInterface = findMuxInterface();
            if (usbInterface == null) {
                throw new IOException("Cannot find usbmux interface on device");
            }

            if (!connection.claimInterface(usbInterface, true)) {
                throw new IOException("Cannot claim USB interface");
            }

            // Find bulk endpoints
            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                UsbEndpoint ep = usbInterface.getEndpoint(i);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        endpointIn = ep;
                    } else {
                        endpointOut = ep;
                    }
                }
            }

            if (endpointIn == null || endpointOut == null) {
                throw new IOException("Cannot find bulk IN/OUT endpoints");
            }

            // Allocate reusable receive buffer
            receiveBuffer = new byte[endpointIn.getMaxPacketSize()];

            Log.i(TAG, "USB connection opened. IN maxPacket=" + endpointIn.getMaxPacketSize()
                    + " OUT maxPacket=" + endpointOut.getMaxPacketSize());
            return true;

        } catch (IOException e) {
            // Clean up on any failure during open
            close();
            throw e;
        }
    }

    private UsbInterface findMuxInterface() {
        // First pass: look for the Apple-specific usbmux interface
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 0xFF
                    && iface.getInterfaceSubclass() == 0xFE
                    && iface.getInterfaceProtocol() == 2) {
                Log.i(TAG, "Found usbmux interface at index " + i);
                return iface;
            }
        }

        // Fallback: look for any vendor-specific interface with bulk endpoints
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 0xFF && hasBulkEndpoints(iface)) {
                Log.i(TAG, "Found vendor-specific interface at index " + i);
                return iface;
            }
        }

        return null;
    }

    private boolean hasBulkEndpoints(UsbInterface iface) {
        boolean hasIn = false, hasOut = false;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) hasIn = true;
                else hasOut = true;
            }
        }
        return hasIn && hasOut;
    }

    /**
     * Send a usbmux connect request to establish a TCP connection
     * to lockdownd (port 62078) on the iOS device.
     *
     * usbmux connect packet format (all little-endian except port):
     *   Header: length(4) + version(4) + msg_type(4) + tag(4)
     *   Body:   port(2, big-endian) + reserved(2) + device_id(4)
     */
    public boolean connectToLockdownd() throws IOException {
        int tag = currentTag++;
        int totalLength = USBMUX_HEADER_SIZE + USBMUX_CONNECT_BODY_SIZE;

        ByteBuffer packet = ByteBuffer.allocate(totalLength);
        packet.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        packet.putInt(totalLength);          // length
        packet.putInt(USBMUX_VERSION);       // version
        packet.putInt(USBMUX_MSG_CONNECT);   // message type
        packet.putInt(tag);                  // tag

        // Body: port in network byte order (big-endian)
        packet.putShort(Short.reverseBytes((short) LOCKDOWND_PORT));
        packet.putShort((short) 0);          // reserved
        packet.putInt(0);                    // device ID (0 for direct USB)

        sendRaw(packet.array());

        // Read response
        byte[] response = receiveRaw();
        if (response == null || response.length < USBMUX_HEADER_SIZE) {
            throw new IOException("Invalid usbmux response (too short: "
                    + (response != null ? response.length : 0) + " bytes)");
        }

        // Parse response header
        ByteBuffer respBuf = ByteBuffer.wrap(response);
        respBuf.order(ByteOrder.LITTLE_ENDIAN);
        int respLen = respBuf.getInt(0);
        int respVersion = respBuf.getInt(4);
        int respType = respBuf.getInt(8);
        int respTag = respBuf.getInt(12);

        // Result code is present only in result-type messages (20+ bytes)
        if (respType != USBMUX_MSG_RESULT) {
            throw new IOException("Unexpected usbmux response type: " + respType);
        }

        if (response.length < 20) {
            throw new IOException("usbmux result message too short: " + response.length);
        }

        int resultCode = respBuf.getInt(16);
        Log.i(TAG, "usbmux response: type=" + respType + " tag=" + respTag
                + " result=" + resultCode);

        if (resultCode != 0) {
            throw new IOException("usbmux connect failed with result code: " + resultCode);
        }

        return true;
    }

    /**
     * Send raw data over the USB bulk OUT endpoint.
     * Uses the data array directly with offset/length to avoid per-chunk allocation.
     */
    public void sendRaw(byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int remaining = data.length - offset;
            int chunkSize = Math.min(remaining, endpointOut.getMaxPacketSize());

            int sent = connection.bulkTransfer(endpointOut, data, offset, chunkSize, USB_TIMEOUT_MS);
            if (sent < 0) {
                throw new IOException("USB bulk OUT transfer failed at offset " + offset);
            }
            offset += sent;
        }
    }

    /**
     * Receive raw data from the USB bulk IN endpoint.
     * Reads the length prefix first, then collects the full message into a
     * pre-allocated buffer to minimize GC pressure.
     */
    public byte[] receiveRaw() throws IOException {
        // First read to get the header (at least 4 bytes for the length field)
        int received = connection.bulkTransfer(endpointIn, receiveBuffer, receiveBuffer.length, USB_TIMEOUT_MS);
        if (received < 4) {
            throw new IOException("Failed to receive response header (got " + received + " bytes)");
        }

        // Parse expected total length from first 4 bytes (LE)
        int totalLength = ByteBuffer.wrap(receiveBuffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (totalLength <= 0 || totalLength > MAX_RESPONSE_SIZE) {
            throw new IOException("Invalid response length: " + totalLength
                    + " (max " + MAX_RESPONSE_SIZE + ")");
        }

        // Pre-allocate exact result buffer
        byte[] result = new byte[totalLength];
        int filled = Math.min(received, totalLength);
        System.arraycopy(receiveBuffer, 0, result, 0, filled);

        // Read remaining data if needed, reusing receiveBuffer
        while (filled < totalLength) {
            int n = connection.bulkTransfer(endpointIn, receiveBuffer, receiveBuffer.length, USB_TIMEOUT_MS);
            if (n < 0) {
                throw new IOException("USB bulk IN transfer failed, expected " + totalLength
                        + " bytes but got " + filled);
            }
            int toCopy = Math.min(n, totalLength - filled);
            System.arraycopy(receiveBuffer, 0, result, filled, toCopy);
            filled += toCopy;
        }

        return result;
    }

    public void close() {
        if (connection != null) {
            if (usbInterface != null) {
                connection.releaseInterface(usbInterface);
            }
            connection.close();
            connection = null;
        }
        endpointIn = null;
        endpointOut = null;
        usbInterface = null;
        receiveBuffer = null;
        Log.i(TAG, "USB connection closed");
    }

    public boolean isConnected() {
        return connection != null && endpointIn != null && endpointOut != null;
    }

    public UsbDevice getDevice() { return device; }
    public void setDevice(UsbDevice device) { this.device = device; }
}
