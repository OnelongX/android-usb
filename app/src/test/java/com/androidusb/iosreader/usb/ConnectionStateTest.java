package com.androidusb.iosreader.usb;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionStateTest {

    @Test
    public void testCanConnect() {
        assertTrue(ConnectionState.IDLE.canConnect());
        assertTrue(ConnectionState.DISCONNECTED.canConnect());
        assertTrue(ConnectionState.ERROR.canConnect());

        assertFalse(ConnectionState.CONNECTING.canConnect());
        assertFalse(ConnectionState.CONNECTED.canConnect());
        assertFalse(ConnectionState.READING.canConnect());
    }

    @Test
    public void testCanRead() {
        assertTrue(ConnectionState.CONNECTED.canRead());

        assertFalse(ConnectionState.IDLE.canRead());
        assertFalse(ConnectionState.CONNECTING.canRead());
        assertFalse(ConnectionState.READING.canRead());
        assertFalse(ConnectionState.DISCONNECTED.canRead());
        assertFalse(ConnectionState.ERROR.canRead());
    }

    @Test
    public void testIsActive() {
        assertTrue(ConnectionState.CONNECTING.isActive());
        assertTrue(ConnectionState.READING.isActive());

        assertFalse(ConnectionState.IDLE.isActive());
        assertFalse(ConnectionState.CONNECTED.isActive());
        assertFalse(ConnectionState.DISCONNECTED.isActive());
        assertFalse(ConnectionState.ERROR.isActive());
    }
}
