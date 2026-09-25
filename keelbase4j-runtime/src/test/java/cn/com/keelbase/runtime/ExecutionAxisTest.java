// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The execution axis, derived rather than stored — the four states of the frozen v2's
 * {@code executionState}, each from the row that should produce it.
 *
 * <p>Plain unit tests, because the derivation is a function of a row and a clock: crafting the row is
 * the whole setup, and running an application to ask what five minutes after a claim means would be
 * slower without being more convincing.
 */
class ExecutionAxisTest {

    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

    @Test
    void aRowThatWasNotApprovedHasNoExecutionDimension() {
        for (String status : new String[] {
                ConfirmationLifecycle.PENDING, ConfirmationLifecycle.DECLINED, ConfirmationLifecycle.TIMEOUT}) {
            ConfirmationRequest row = row(status);

            assertNull(ExecutionAxis.derive(row, NOW),
                    "reporting not_started for a " + status + " row would imply something is still going to run");
        }
    }

    @Test
    void anApprovedRowWithNoAttemptYetIsNotStarted() {
        ConfirmationRequest row = row(ConfirmationLifecycle.APPROVED);

        assertEquals(ExecutionAxis.NOT_STARTED, ExecutionAxis.derive(row, NOW));
    }

    @Test
    void anAttemptInsideItsLeaseIsRunning() {
        ConfirmationRequest row = row(ConfirmationLifecycle.APPROVED);
        row.setExecutionClaimedAt(NOW.minusMillis(ExecutionAxis.LEASE_MILLIS - 1));

        assertEquals(ExecutionAxis.RUNNING, ExecutionAxis.derive(row, NOW));
    }

    @Test
    void anAttemptPastItsLeaseWithoutAResultIsFailed() {
        ConfirmationRequest row = row(ConfirmationLifecycle.APPROVED);
        row.setExecutionClaimedAt(NOW.minusMillis(ExecutionAxis.LEASE_MILLIS + 1));

        assertEquals(ExecutionAxis.FAILED, ExecutionAxis.derive(row, NOW),
                "we cannot tell a crash from a hang, and both are reported the same");
    }

    @Test
    void aRecordedFailureIsAlsoFailed() {
        ConfirmationRequest row = row(ConfirmationLifecycle.APPROVED);
        row.setExecutionClaimedAt(NOW.minusMillis(ExecutionAxis.LEASE_MILLIS + 1));
        row.setExecutionError("the tool refused");

        assertEquals(ExecutionAxis.FAILED, ExecutionAxis.derive(row, NOW),
                "the error changes what a console says, not which state it is");
    }

    @Test
    void aLandedAttemptIsSucceeded() {
        ConfirmationRequest row = row(ConfirmationLifecycle.APPROVED);
        row.setExecutionClaimedAt(NOW.minusSeconds(30));
        row.setExecutedAt(NOW.minusSeconds(29));

        assertEquals(ExecutionAxis.SUCCEEDED, ExecutionAxis.derive(row, NOW),
                "a result outranks the lease: this one is done, not still running");
    }

    private static ConfirmationRequest row(String status) {
        ConfirmationRequest row = new ConfirmationRequest("t", "create_followup", "{}", "alice", "R3");
        row.setStatus(status);
        return row;
    }
}
