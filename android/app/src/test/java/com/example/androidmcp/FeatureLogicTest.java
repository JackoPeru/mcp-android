package com.example.androidmcp;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure logic across the new capture/telephony/health features. */
public final class FeatureLogicTest {
    @Test public void captureNamesAreStrictAndTraversalSafe() {
        assertTrue(CaptureStore.isValidName("photo-1700000000000-ab12cd.jpg"));
        assertTrue(CaptureStore.isValidName("audio-1700000000000-ab12cd.m4a"));
        assertTrue(CaptureStore.isValidName("screen-1700000000000-ab12cd.mp4"));
        assertFalse(CaptureStore.isValidName("../photo-1700000000000-ab12cd.jpg"));
        assertFalse(CaptureStore.isValidName("photo-1700000000000-ab12cd.exe"));
        assertFalse(CaptureStore.isValidName("photo-17000000000-ab12cd.jpg"));
        assertFalse(CaptureStore.isValidName("other-1700000000000-ab12cd.jpg"));
        assertFalse(CaptureStore.isValidName(null));
        assertEquals("image/jpeg", CaptureStore.mimeType("photo-1-ab12cd.jpg"));
        assertEquals("video/mp4", CaptureStore.mimeType("screen-1-ab12cd.mp4"));
        assertEquals("audio/ogg", CaptureStore.mimeType("audio-1-ab12cd.ogg"));
    }

    @Test public void phoneNumbersAcceptHumanFormatsButRejectInjection() {
        assertTrue(SmsController.isValidNumber("+39 333 1234567"));
        assertTrue(SmsController.isValidNumber("3331234567"));
        assertTrue(SmsController.isValidNumber("(02) 1234-5678"));
        assertFalse(SmsController.isValidNumber(""));
        assertFalse(SmsController.isValidNumber("abc"));
        assertFalse(SmsController.isValidNumber("123;rm -rf /"));
        assertFalse(SmsController.isValidNumber("12"));
        assertEquals("+39 333 1234567", SmsController.normalizeNumber("  +39   333 1234567 "));
    }

    @Test public void confirmationsAreSingleUseAndBoundToTheExactPayload() {
        String id = PendingActions.create("sms", "+393331234567", "ciao");
        assertNotNull(PendingActions.get(id));
        // Not confirmed yet: sending must fail closed.
        assertFalse(PendingActions.consumeConfirmed(id, "sms", "+393331234567", "ciao"));
        PendingActions.confirm(id);
        // Wrong body or recipient cannot ride an existing confirmation.
        assertFalse(PendingActions.consumeConfirmed(id, "sms", "+393331234567", "altro"));
        assertFalse(PendingActions.consumeConfirmed(id, "sms", "+399999999999", "ciao"));
        assertFalse(PendingActions.consumeConfirmed(id, "call", "+393331234567", "ciao"));
        assertTrue(PendingActions.consumeConfirmed(id, "sms", "+393331234567", "ciao"));
        // Used: a retry after the platform call must not fire again.
        assertFalse(PendingActions.consumeConfirmed(id, "sms", "+393331234567", "ciao"));
        assertNull(PendingActions.get(id));
    }

    @Test public void cancelledConfirmationNeverSends() {
        String id = PendingActions.create("call", "+393331234567", "");
        PendingActions.confirm(id);
        PendingActions.cancel(id);
        assertFalse(PendingActions.consumeConfirmed(id, "call", "+393331234567", ""));
    }

    @Test public void screenRecordingSizesStayEvenAndBounded() {
        int[] scaled = ScreenRecordService.scaledSize(2400, 1080, 1280);
        assertTrue(scaled[0] <= 1280);
        assertEquals(0, scaled[0] % 2);
        assertEquals(0, scaled[1] % 2);
        int[] untouched = ScreenRecordService.scaledSize(800, 600, 1280);
        assertEquals(800, untouched[0]);
        assertEquals(600, untouched[1]);
        int[] capped = ScreenRecordService.scaledSize(10000, 10000, 100);
        assertTrue(capped[0] <= 100 + 1);
    }

    @Test public void healthTypesMapToReadPermissions() {
        List<String> types = HealthController.supportedTypes();
        assertTrue(types.contains("steps"));
        assertTrue(types.contains("sleep"));
        assertTrue(types.contains("heart_rate"));
        for (String type : types) {
            String permission = HealthController.readPermission(type);
            assertTrue(permission.contains("READ_"));
            assertTrue(permission.startsWith("android.permission.health."));
        }
        assertTrue(HealthController.readPermission("steps").endsWith("READ_STEPS"));
        assertTrue(HealthController.readPermission("sleep").endsWith("READ_SLEEP"));
    }

    @Test public void newMethodsDoNotHoldTheUiLockOrPulseTheVeil() {
        for (String method : new String[]{
                "photo_capture", "torch_set", "audio_record_start", "audio_record_stop",
                "screen_record_start", "screen_record_stop", "capture_stop_all",
                "sms_prepare", "sms_send", "call_dial", "call", "contacts_search",
                "health_read", "health_aggregate", "location_get", "speak", "alarm_set",
                "timer_set", "current_app", "network_status", "relay_status", "relay_configure",
        }) {
            assertEquals(method, RpcPolicy.LockDomain.NONE, RpcPolicy.lockDomain(method));
            assertFalse(method, RpcPolicy.showsVeil(method));
        }
    }
}
