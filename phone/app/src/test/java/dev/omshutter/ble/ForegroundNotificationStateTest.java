package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class ForegroundNotificationStateTest {
    @Test
    public void identicalRenderedNotificationStateDeduplicates() {
        ForegroundNotificationState first = state(
                "Camera", "Ready", "", true, false, false, false);
        ForegroundNotificationState same = state(
                "Camera", "Ready", "", true, false, false, false);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertEquals("Camera", first.getTitle());
        assertEquals("Ready", first.getText());
        assertEquals(
                ForegroundNotificationState.PrimaryAction.TAKE_PHOTO,
                first.getPrimaryAction());
    }

    @Test
    public void equalityTracksOnlyContentThatIsActuallyRendered() {
        ForegroundNotificationState idle = state(
                "Camera", "Ready", "", false, false, false, false);
        ForegroundNotificationState busyWithoutAction = state(
                "Camera", "Ready", "", true, true, false, false);
        ForegroundNotificationState halfHeld = state(
                "Camera", "Ready", "", true, false, false, true);
        ForegroundNotificationState shutterHeld = state(
                "Camera", "Ready", "", true, false, true, false);

        assertEquals(idle, busyWithoutAction);
        assertEquals(halfHeld, shutterHeld);
        assertNotEquals(idle, halfHeld);
    }

    @Test
    public void everyMeaningfulRenderedChangeInvalidatesTheDedupeKey() {
        ForegroundNotificationState baseline = state(
                "Camera", "Ready", "", true, false, false, false);

        assertNotEquals(baseline, state(
                "Other", "Ready", "", true, false, false, false));
        assertNotEquals(baseline, state(
                "Camera", "Connecting", "", true, false, false, false));
        assertNotEquals(baseline, state(
                "Camera", "Ready", "Working", true, false, false, false));
        assertNotEquals(baseline, state(
                "Camera", "Ready", "", false, false, false, false));
        assertNotEquals(
                baseline,
                ForegroundNotificationState.create(
                        "App", "Camera", "Ready", "",
                        true, false, true, false, false,
                        "Localized photo", "Release", "Disconnect"));
        assertNotEquals(
                baseline,
                ForegroundNotificationState.create(
                        "App", "Camera", "Ready", "",
                        true, false, true, false, false,
                        "Photo", "Release", "Localized disconnect"));
    }

    @Test
    public void fallbackTitleAndDetailSeparatorMatchNotificationRendering() {
        ForegroundNotificationState rendered =
                ForegroundNotificationState.create(
                        "App", "", "Connecting", "Camera found",
                        false, false, true, false, false,
                        "Photo", "Release", "Disconnect");

        assertEquals("App", rendered.getTitle());
        assertEquals("Connecting — Camera found", rendered.getText());
        assertEquals(
                ForegroundNotificationState.PrimaryAction.NONE,
                rendered.getPrimaryAction());
        assertEquals("", rendered.getPrimaryActionLabel());
        assertEquals("Disconnect", rendered.getDisconnectLabel());
    }

    @Test
    public void captureRecoverySuppressesTakePhotoButNeverRelease() {
        ForegroundNotificationState blocked = ForegroundNotificationState.create(
                "App", "Camera", "Ready", "Recovery required",
                true, false, false, false, false,
                "Photo", "Release", "Disconnect");
        ForegroundNotificationState heldWhileBlockedAndBusy =
                ForegroundNotificationState.create(
                        "App", "Camera", "Ready", "Recovery required",
                        true, true, false, true, false,
                        "Photo", "Release", "Disconnect");

        assertEquals(
                ForegroundNotificationState.PrimaryAction.NONE,
                blocked.getPrimaryAction());
        assertEquals(
                ForegroundNotificationState.PrimaryAction.RELEASE_ALL,
                heldWhileBlockedAndBusy.getPrimaryAction());
    }

    private static ForegroundNotificationState state(
            String cameraName,
            String status,
            String detail,
            boolean ready,
            boolean busy,
            boolean shutterHeld,
            boolean halfHeld) {
        return ForegroundNotificationState.create(
                "App",
                cameraName,
                status,
                detail,
                ready,
                busy,
                true,
                shutterHeld,
                halfHeld,
                "Photo",
                "Release",
                "Disconnect");
    }
}
