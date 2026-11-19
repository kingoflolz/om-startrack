package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StarInitializationRecoveryGateTest {
    private static final int EPOCH = 7;

    @Test
    public void repeatedSameGenerationAndCompletenessCannotReopenWindows() {
        StarInitializationRecoveryGate gate = exhaustedGate(available(1L, true));

        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(1L, true)));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(1L, true)));
    }

    @Test
    public void changedGenerationReservesExactlyOneProbe() {
        StarInitializationRecoveryGate gate = exhaustedGate(available(1L, true));

        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, available(2L, true)));
        assertEquals(available(2L, true), gate.getExhaustedSignature(EPOCH));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(2L, true)));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, available(3L, true)));
    }

    @Test
    public void sameGenerationIncompleteToCompleteReservesOneProbe() {
        StarInitializationRecoveryGate gate = exhaustedGate(available(7L, false));

        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, available(7L, true)));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(7L, true)));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(7L, false)));
    }

    @Test
    public void configAndMutableAuthorityChangesEachReserveOneProbe() {
        StarInitializationRecoveryGate gate = exhaustedGate(
                available(7L, true, 10L, 3L, 1, 2, 3));

        StarInitializationRecoveryGate.Signature newConfig =
                available(7L, true, 11L, 3L, 1, 2, 3);
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, newConfig));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, newConfig));

        StarInitializationRecoveryGate.Signature correctedBounds =
                available(7L, true, 11L, 3L, 1, 2, 4);
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, correctedBounds));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, correctedBounds));

        StarInitializationRecoveryGate.Signature restartedWorker =
                available(7L, true, 11L, 4L, 1, 2, 4);
        // Worker restart counters can churn in a 500 ms failure loop; without a config or exact
        // mutable-authority change they must not create another read window.
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, restartedWorker));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, restartedWorker));
    }

    @Test
    public void unfencedUnavailableDoesNotClearExhaustedAvailableEvidence() {
        StarInitializationRecoveryGate gate = exhaustedGate(
                available(7L, true, 10L, 3L, 1, 2, 3));

        gate.observeUnavailable(EPOCH, false);

        assertTrue(gate.isExhausted(EPOCH));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(
                        EPOCH, available(7L, true, 10L, 3L, 1, 2, 3)));

        gate.observeUnavailable(EPOCH, true);
        assertFalse(gate.isExhausted(EPOCH));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(
                        EPOCH, available(7L, true, 10L, 3L, 1, 2, 3)));
    }

    @Test
    public void malformedStateAAllowsOneLaterValidProbe() {
        StarInitializationRecoveryGate gate = new StarInitializationRecoveryGate();
        gate.resetForConnection(EPOCH);
        assertTrue(gate.beginFence(EPOCH));
        assertTrue(gate.recordUnknownStateA(EPOCH));
        assertTrue(gate.markExhausted(EPOCH));
        assertTrue(gate.isExhausted(EPOCH));
        assertNull(gate.getExhaustedSignature(EPOCH));

        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, available(9L, false)));

        // If that probe also fails before yielding a parsed A, retain its reservation instead of
        // treating malformed input as permission to reopen the same notification forever.
        assertTrue(gate.beginFence(EPOCH));
        assertTrue(gate.recordUnknownStateA(EPOCH));
        assertTrue(gate.markExhausted(EPOCH));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(9L, false)));
    }

    @Test
    public void newestKnownFenceEndpointDefinesExhaustion() {
        StarInitializationRecoveryGate gate = new StarInitializationRecoveryGate();
        gate.resetForConnection(EPOCH);
        gate.beginFence(EPOCH);
        gate.recordStateA(EPOCH, available(4L, true));
        gate.recordStateB(EPOCH, available(5L, false));
        gate.markExhausted(EPOCH);

        assertEquals(available(5L, false), gate.getExhaustedSignature(EPOCH));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.BLOCK_REPEATED,
                gate.classifyNotification(EPOCH, available(5L, false)));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, available(5L, true)));
    }

    @Test
    public void successStableUnavailableAndReconnectClearSuppression() {
        StarInitializationRecoveryGate gate = exhaustedGate(available(3L, true));
        gate.clearAfterSuccess(EPOCH);
        assertFalse(gate.isExhausted(EPOCH));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH, available(3L, true)));

        gate = exhaustedGate(available(3L, true));
        gate.observeUnavailable(EPOCH, true);
        assertFalse(gate.isExhausted(EPOCH));

        gate = exhaustedGate(available(3L, true));
        gate.resetForConnection(EPOCH + 1);
        assertFalse(gate.isExhausted(EPOCH + 1));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.STALE_EPOCH,
                gate.classifyNotification(EPOCH, available(4L, true)));
        assertEquals(
                StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE,
                gate.classifyNotification(EPOCH + 1, available(3L, true)));
    }

    @Test
    public void signatureRejectsNoncanonicalAvailability() {
        try {
            StarInitializationRecoveryGate.Signature.of(false, 2L, false);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected noncanonical signature rejection");
    }

    private static StarInitializationRecoveryGate exhaustedGate(
            StarInitializationRecoveryGate.Signature signature) {
        StarInitializationRecoveryGate gate = new StarInitializationRecoveryGate();
        gate.resetForConnection(EPOCH);
        gate.beginFence(EPOCH);
        gate.recordStateA(EPOCH, signature);
        gate.markExhausted(EPOCH);
        return gate;
    }

    private static StarInitializationRecoveryGate.Signature available(
            long generation, boolean completeMutable) {
        return StarInitializationRecoveryGate.Signature.of(
                true, generation, completeMutable);
    }

    private static StarInitializationRecoveryGate.Signature available(
            long generation,
            boolean completeMutable,
            long configGeneration,
            long workerGeneration,
            int... mutableAuthority) {
        return StarInitializationRecoveryGate.Signature.of(
                true,
                generation,
                completeMutable,
                configGeneration,
                workerGeneration,
                mutableAuthority);
    }
}
