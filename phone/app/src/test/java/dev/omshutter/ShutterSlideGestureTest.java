package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShutterSlideGestureTest {
    @Test
    public void shutterToLockCommitsHeldPressWithoutReleasingOnPointerUp() {
        ShutterSlideGesture gesture = new ShutterSlideGesture();

        ShutterSlideGesture.Result down = gesture.onPointerDown(
                1_000L, ShutterSlideGesture.Target.SHUTTER);
        assertResult(down, true, true, false, false, false);
        assertTrue(gesture.isPressingShutter());

        assertResult(
                gesture.onPointerMove(1_100L, ShutterSlideGesture.Target.OUTSIDE),
                true, false, false, false, false);
        ShutterSlideGesture.Result crossed = gesture.onPointerMove(
                1_250L, ShutterSlideGesture.Target.LOCK);
        assertResult(crossed, true, false, false, true, true);
        assertTrue(gesture.isLocked());
        assertFalse(gesture.isPressingShutter());

        assertResult(
                gesture.onPointerUp(1_300L, ShutterSlideGesture.Target.LOCK),
                true, false, false, false, true);
        assertTrue(gesture.isLocked());
        assertFalse(gesture.isPointerActive());
    }

    @Test
    public void ordinaryShutterTapAlwaysReleasesAndNeverLocks() {
        ShutterSlideGesture gesture = new ShutterSlideGesture();

        assertResult(
                gesture.onPointerDown(10L, ShutterSlideGesture.Target.SHUTTER),
                true, true, false, false, false);
        assertResult(
                gesture.onPointerUp(20L, ShutterSlideGesture.Target.SHUTTER),
                true, false, true, false, false);
        assertFalse(gesture.isLocked());
    }

    @Test
    public void lockTargetTapOrWrongDirectionDragCannotLock() {
        ShutterSlideGesture gesture = new ShutterSlideGesture();

        assertResult(
                gesture.onPointerDown(100L, ShutterSlideGesture.Target.LOCK),
                true, false, false, false, false);
        assertResult(
                gesture.onPointerMove(110L, ShutterSlideGesture.Target.SHUTTER),
                true, false, false, false, false);
        assertResult(
                gesture.onPointerUp(120L, ShutterSlideGesture.Target.SHUTTER),
                true, false, false, false, false);
        assertFalse(gesture.isLocked());
    }

    @Test
    public void lockedTimerToCenterRequestsReleaseAndRetainsRetryUntilConfirmed() {
        ShutterSlideGesture gesture = lockedGesture(5_000L);

        assertResult(
                gesture.onPointerDown(6_000L, ShutterSlideGesture.Target.LOCK),
                true, false, false, false, true);
        assertResult(
                gesture.onPointerMove(6_010L, ShutterSlideGesture.Target.OUTSIDE),
                true, false, false, false, true);
        assertResult(
                gesture.onPointerMove(6_020L, ShutterSlideGesture.Target.SHUTTER),
                true, false, true, false, true);
        assertResult(
                gesture.onPointerMove(6_030L, ShutterSlideGesture.Target.LOCK),
                true, false, false, false, true);
        assertResult(
                gesture.onPointerUp(6_040L, ShutterSlideGesture.Target.SHUTTER),
                true, false, false, false, true);
        assertTrue(gesture.isLocked());

        assertResult(
                gesture.confirmReleased(6_050L),
                true, false, false, true, false);
        assertFalse(gesture.isLocked());
    }

    @Test
    public void destinationReachedOnlyOnPointerUpStillLocksAndRequestsUnlock() {
        ShutterSlideGesture lock = new ShutterSlideGesture();
        assertResult(
                lock.onPointerDown(1_000L, ShutterSlideGesture.Target.SHUTTER),
                true, true, false, false, false);
        assertResult(
                lock.onPointerUp(1_100L, ShutterSlideGesture.Target.LOCK),
                true, false, false, true, true);

        ShutterSlideGesture unlock = lockedGesture(2_000L);
        assertResult(
                unlock.onPointerDown(2_100L, ShutterSlideGesture.Target.LOCK),
                true, false, false, false, true);
        assertResult(
                unlock.onPointerUp(2_200L, ShutterSlideGesture.Target.SHUTTER),
                true, false, true, false, true);
        assertTrue(unlock.isLocked());
    }

    @Test
    public void tappingCenterWhileLockedDoesNotBypassReverseSlide() {
        ShutterSlideGesture gesture = lockedGesture(0L);

        assertResult(
                gesture.onPointerDown(10L, ShutterSlideGesture.Target.SHUTTER),
                true, false, false, false, true);
        assertResult(
                gesture.onPointerUp(20L, ShutterSlideGesture.Target.SHUTTER),
                true, false, false, false, true);
        assertTrue(gesture.isLocked());
    }

    @Test
    public void cancelReleasesUncommittedPressButPreservesCommittedLock() {
        ShutterSlideGesture pressing = new ShutterSlideGesture();
        pressing.onPointerDown(0L, ShutterSlideGesture.Target.SHUTTER);
        assertResult(
                pressing.onPointerCancel(10L),
                true, false, true, false, false);

        ShutterSlideGesture locked = new ShutterSlideGesture();
        locked.onPointerDown(0L, ShutterSlideGesture.Target.SHUTTER);
        locked.onPointerMove(10L, ShutterSlideGesture.Target.LOCK);
        assertResult(
                locked.onPointerCancel(20L),
                true, false, false, false, true);
        assertTrue(locked.isLocked());

        locked.onPointerDown(30L, ShutterSlideGesture.Target.LOCK);
        assertResult(
                locked.onPointerCancel(40L),
                true, false, false, false, true);
        assertTrue(locked.isLocked());
    }

    @Test
    public void lockedElapsedTimeStartsAtCrossingAndNeverRegresses() {
        ShutterSlideGesture gesture = new ShutterSlideGesture();
        gesture.onPointerDown(1_000L, ShutterSlideGesture.Target.SHUTTER);
        gesture.onPointerMove(2_000L, ShutterSlideGesture.Target.LOCK);

        assertEquals(0L, gesture.lockedElapsedMs(1_900L));
        assertEquals(750L, gesture.lockedElapsedMs(2_750L));
        assertEquals(750L, gesture.lockedElapsedMs(2_500L));
        assertEquals(1_000L, gesture.lockedElapsedMs(3_000L));
    }

    @Test
    public void accessibilityActionsAreExplicitLockAndUnlockEquivalents() {
        ShutterSlideGesture gesture = new ShutterSlideGesture();

        assertResult(
                gesture.lockForAccessibility(20_000L),
                true, true, false, true, true);
        assertResult(
                gesture.lockForAccessibility(20_010L),
                false, false, false, false, true);
        assertResult(
                gesture.unlockForAccessibility(20_020L),
                true, false, true, false, true);
        assertResult(
                gesture.unlockForAccessibility(20_030L),
                true, false, true, false, true);
        assertResult(
                gesture.confirmReleased(20_040L),
                true, false, false, true, false);
    }

    @Test
    public void restoredLockContinuesSavedMonotonicTimerWithoutCameraEffects() {
        ShutterSlideGesture gesture = new ShutterSlideGesture();

        gesture.restoreLocked(5_000L, 8_000L);

        assertTrue(gesture.isLocked());
        assertFalse(gesture.isPointerActive());
        assertEquals(5_000L, gesture.lockedAtElapsedMs());
        assertEquals(3_000L, gesture.lockedElapsedMs(8_000L));
        assertEquals(3_500L, gesture.lockedElapsedMs(8_500L));
        assertEquals(3_500L, gesture.lockedElapsedMs(8_250L));
    }

    @Test
    public void restoreClampsInvalidOrFutureTimerAnchorsToNow() {
        ShutterSlideGesture invalid = new ShutterSlideGesture();
        invalid.restoreLocked(-1L, 10_000L);
        assertEquals(10_000L, invalid.lockedAtElapsedMs());
        assertEquals(0L, invalid.lockedElapsedMs(10_000L));

        ShutterSlideGesture future = new ShutterSlideGesture();
        future.restoreLocked(20_000L, 10_000L);
        assertEquals(10_000L, future.lockedAtElapsedMs());
        assertEquals(0L, future.lockedElapsedMs(10_000L));
    }

    @Test
    public void resetReleasesOwnedHoldAndIsIdempotent() {
        ShutterSlideGesture locked = lockedGesture(100L);
        assertResult(
                locked.reset(200L),
                true, false, true, true, false);
        assertResult(
                locked.reset(300L),
                false, false, false, false, false);

        ShutterSlideGesture pressing = new ShutterSlideGesture();
        pressing.onPointerDown(0L, ShutterSlideGesture.Target.SHUTTER);
        assertResult(
                pressing.reset(5L),
                true, false, true, false, false);
    }

    @Test
    public void equalThirdAndExplicitBoundsHitTestsHaveExclusiveOuterEdges() {
        assertEquals(
                ShutterSlideGesture.Target.AF,
                ShutterSlideGesture.targetAt(0f, 0f, 300, 60));
        assertEquals(
                ShutterSlideGesture.Target.SHUTTER,
                ShutterSlideGesture.targetAt(100f, 30f, 300, 60));
        assertEquals(
                ShutterSlideGesture.Target.LOCK,
                ShutterSlideGesture.targetAt(200f, 59.9f, 300, 60));
        assertEquals(
                ShutterSlideGesture.Target.OUTSIDE,
                ShutterSlideGesture.targetAt(300f, 30f, 300, 60));
        assertEquals(
                ShutterSlideGesture.Target.OUTSIDE,
                ShutterSlideGesture.targetAt(Float.NaN, 30f, 300, 60));

        ShutterSlideGesture.Bounds af = new ShutterSlideGesture.Bounds(0f, 0f, 90f, 50f);
        ShutterSlideGesture.Bounds shutter =
                new ShutterSlideGesture.Bounds(100f, 0f, 190f, 50f);
        ShutterSlideGesture.Bounds lock =
                new ShutterSlideGesture.Bounds(200f, 0f, 290f, 50f);
        assertEquals(
                ShutterSlideGesture.Target.OUTSIDE,
                ShutterSlideGesture.targetAt(95f, 20f, af, shutter, lock));
        assertEquals(
                ShutterSlideGesture.Target.SHUTTER,
                ShutterSlideGesture.targetAt(100f, 20f, af, shutter, lock));
        assertEquals(
                ShutterSlideGesture.Target.OUTSIDE,
                ShutterSlideGesture.targetAt(290f, 20f, af, shutter, lock));
    }

    private static ShutterSlideGesture lockedGesture(long nowElapsedMs) {
        ShutterSlideGesture gesture = new ShutterSlideGesture();
        assertTrue(gesture.lockForAccessibility(nowElapsedMs).locked());
        return gesture;
    }

    private static void assertResult(
            ShutterSlideGesture.Result result,
            boolean consumed,
            boolean press,
            boolean release,
            boolean lockChanged,
            boolean locked) {
        assertEquals(consumed, result.consumed());
        assertEquals(press, result.pressShutter());
        assertEquals(release, result.releaseShutter());
        assertEquals(lockChanged, result.lockChanged());
        assertEquals(locked, result.locked());
    }
}
