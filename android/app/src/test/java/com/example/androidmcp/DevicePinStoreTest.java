package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DevicePinStoreTest {
    @Test public void pinFormatIsStrictlyNumeric() {
        assertTrue(DevicePinStore.isValidPin("0000"));
        assertTrue(DevicePinStore.isValidPin("1234567890123456"));
        assertFalse(DevicePinStore.isValidPin(null));
        assertFalse(DevicePinStore.isValidPin(""));
        assertFalse(DevicePinStore.isValidPin("123"));
        assertFalse(DevicePinStore.isValidPin("12345678901234567"));
        assertFalse(DevicePinStore.isValidPin("12a4"));
        assertFalse(DevicePinStore.isValidPin(" 1234"));
        assertFalse(DevicePinStore.isValidPin("1234 "));
        assertFalse(DevicePinStore.isValidPin("12.4"));
    }
}
