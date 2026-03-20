package com.androidusb.iosreader.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
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

    // usbmuxd protocol constants
    private static final int USBMUX_VERSION = 1;
    private static final int USBMUX_PROTOCOL_TCP = 6;
    private static final int USBMUX_MSG_CONNECT = 2;
    private static final int USBMUX_MSG_RESULT = 1;

    // lockdownd listens on port 62078
    private static final int LOCKDOWND_PORT = 62078;

    private final UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private int currentTag = 1;

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

        // Find the usbmux interface (class 0xFF, subclass 0xFE, protocol 2)
        usbInterface = findMuxInterface();
        if (usbInterface == null) {
            connection.close();
            throw new IOException("Cannot find usbmux interface on device");
        }

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
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
            close();
            throw new IOException("Cannot find bulk IN/OUT endpoints");
        }

        Log.i(TAG, "USB connection opened. IN maxPacket=" + endpointIn.getMaxPacketSize()
                + " OUT maxPacket=" + endpointOut.getMaxPacketSize());
        return true;
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
     */
    public boolean connectToLockdownd() throws IOException {
        // Build the usbmux connect message (binary protocol v1)
        int tag = currentTag++;
        byte[] header = buildUsbMuxHeader(USBMUX_MSG_CONNECT, tag, 20);

        // Connect body: port number (big-endian) + reserved
        ByteBuffer body = ByteBuffer.allocate(12);
        body.order(ByteOrder.LITTLE_ENDIAN);
        // Port in network byte order (big-endian) within the LE struct
        body.putShort((short) ((LOCKDOWND_PORT >> 8) | ((LOCKDOWND_PORT & 0xFF) << 8)));
        body.putShort((short) 0); // reserved
        body.putInt(0); // reserved
        body.putInt(0); // reserved

        byte[] packet = concat(header, body.array());
        sendRaw(packet);

        // Read response
        byte[] response = receiveRaw();
        if (response == null || response.length < 16) {
            throw new IOException("Invalid usbmux response");
        }

        // Parse result code from response (offset 12, 4 bytes LE)
        ByteBuffer respBuf = ByteBuffer.wrap(response);
        respBuf.order(ByteOrder.LITTLE_ENDIAN);
        int respLen = respBuf.getInt(0);
        int respProto = respBuf.getInt(4);
        int respType = respBuf.getInt(8);
        int respTag = respBuf.getInt(12);
        int resultCode = response.length >= 20 ? respBuf.getInt(16) : -1;

        Log.i(TAG, "usbmux response: type=" + respType + " tag=" + respTag
                + " result=" + resultCode);

        if (resultCode != 0) {
            throw new IOException("usbmux connect failed with result code: " + resultCode);
        }

        return true;
    }

    private byte[] buildUsbMuxHeader(int messageType, int tag, int totalLength) {
        ByteBuffer header = ByteBuffer.allocate(8);
        header.order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(totalLength);         // length
        header.putInt(USBMUX_VERSION);      // version
        // Second 8 bytes
        ByteBuffer header2 = ByteBuffer.allocate(8);
        header2.order(ByteOrder.LITTLE_ENDIAN);
        header2.putInt(messageType);        // message type
        header2.putInt(tag);                // tag
        return concat(header.array(), header2.array());
    }

    /**
     * Send raw data over the USB bulk OUT endpoint.
     */
    public void sendRaw(byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int remaining = data.length - offset;
            int chunkSize = Math.min(remaining, endpointOut.getMaxPacketSize());
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(data, offset, chunk, 0, chunkSize);

            int sent = connection.bulkTransfer(endpointOut, chunk, chunkSize, USB_TIMEOUT_MS);
            if (sent < 0) {
                throw new IOException("USB bulk transfer failed at offset " + offset);
            }
            offset += sent;
        }
    }

    /**
     * Receive raw data from the USB bulk IN endpoint.
     */
    public byte[] receiveRaw() throws IOException {
        // First read to get the header (at least 4 bytes for the length field)
        byte[] headerBuf = new byte[endpointIn.getMaxPacketSize()];
        int received = connection.bulkTransfer(endpointIn, headerBuf, headerBuf.length, USB_TIMEOUT_MS);
        if (received < 4) {
            throw new IOException("Failed to receive response header (got " + received + " bytes)");
        }

        // Parse expected total length from first 4 bytes (LE)
        int totalLength = ByteBuffer.wrap(headerBuf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (totalLength <= 0 || totalLength > 1024 * 1024) {
            throw new IOException("Invalid response length: " + totalLength);
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(headerBuf, 0, received);

        // Read remaining data if needed
        while (result.size() < totalLength) {
            byte[] buf = new byte[endpointIn.getMaxPacketSize()];
            int n = connection.bulkTransfer(endpointIn, buf, buf.length, USB_TIMEOUT_MS);
            if (n < 0) {
                throw new IOException("USB read failed, expected " + totalLength
                        + " bytes but got " + result.size());
            }
            result.write(buf, 0, n);
        }

        return result.toByteArray();
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
        Log.i(TAG, "USB connection closed");
    }

    public boolean isConnected() {
        return connection != null && endpointIn != null && endpointOut != null;
    }

    public UsbDevice getDevice() { return device; }
    public void setDevice(UsbDevice device) { this.device = device; }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
