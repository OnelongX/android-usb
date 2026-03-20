package com.androidusb.iosreader.usb;

/**
 * Connection state machine for managing iOS device USB connection lifecycle.
 *
 * State transitions:
 *   IDLE → CONNECTING → CONNECTED → READING → CONNECTED
 *                    ↘         ↗         ↘
 *                     ERROR → IDLE        ERROR
 *                           ↗
 *          CONNECTED → DISCONNECTED → IDLE
 */
public enum ConnectionState {
    /** No device connected, waiting for user action or USB attachment. */
    IDLE,

    /** USB connection being established (open, claim interface, usbmux connect). */
    CONNECTING,

    /** Connected to lockdownd, ready to send queries. */
    CONNECTED,

    /** Currently reading device info from lockdownd. */
    READING,

    /** Device was disconnected (USB detached). */
    DISCONNECTED,

    /** An error occurred. User can retry. */
    ERROR;

    public boolean canConnect() {
        return this == IDLE || this == DISCONNECTED || this == ERROR;
    }

    public boolean canRead() {
        return this == CONNECTED;
    }

    public boolean isActive() {
        return this == CONNECTING || this == READING;
    }
}
