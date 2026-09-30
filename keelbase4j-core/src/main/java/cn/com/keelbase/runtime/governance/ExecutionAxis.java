// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import java.time.Instant;

/**
 * The execution axis of a confirmation — "did the approved write actually run?" (ADR-0016, the frozen
 * v2's).
 *
 * <p>Kept apart from the decision axis ({@code status}) on purpose. The decision axis already
 * guarantees <em>at most one decision</em> — the conditional update is the only arbitration point —
 * but it says nothing about whether the tool ran, so a row could read {@code approved} while the write
 * had never happened and nothing anywhere recorded it. The contract keeps the two axes apart rather
 * than adding an {@code executing} status: a row can legitimately be approved <em>and</em> still
 * running, and widening the state set would move the frozen corpus and every surface that switches on
 * it.
 *
 * <p>What it reports, from the contract's own four states: nothing at all unless the row is
 * {@code approved}; {@code succeeded} once an attempt has landed; {@code running} while an attempt is
 * inside its lease; and {@code failed} once an attempt is older than the lease without a result —
 * which covers both a recorded error and a vanished one, because that difference is wording, not state.
 */
public final class ExecutionAxis {

    public static final String NOT_STARTED = "not_started";
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    /**
     * How long one attempt counts as still running. The contract names this constant and leaves the
     * value to the implementation; five minutes is the reference's, kept here so that both answer the
     * same thing about the same row.
     */
    public static final long LEASE_MILLIS = 5 * 60 * 1000L;

    private ExecutionAxis() {
    }

    /**
     * The outward execution state, or {@code null} when the row has no execution dimension at all — an
     * undecided, declined or timed-out confirmation. Reporting {@code not_started} for those would
     * imply that something is still going to run.
     *
     * <p>{@code executionError} is deliberately not consulted: an attempt that recorded its failure and
     * one that never got to both derive {@code failed}, and the error only changes what a console says
     * about it. See the row's {@code executionError} for the wording.
     */
    public static String derive(ConfirmationRequest row, Instant now) {
        if (!ConfirmationLifecycle.APPROVED.equals(row.getStatus())) {
            return null;
        }
        if (row.getExecutedAt() != null) {
            return SUCCEEDED;
        }
        Instant claimedAt = row.getExecutionClaimedAt();
        if (claimedAt == null) {
            return NOT_STARTED;
        }
        return now.toEpochMilli() - claimedAt.toEpochMilli() < LEASE_MILLIS ? RUNNING : FAILED;
    }

    /**
     * Whether a new attempt may take this row: it has not succeeded, and any existing claim is older
     * than the lease — because a fresh claim means an attempt may be running right now.
     *
     * <p>This asks the same question the claim's SQL condition asks in the database, and the boundary
     * is the same one {@link #derive} uses to say {@code running}: exactly one of the two answers.
     *
     * <p>The retry entry consults it to refuse with a reason, but the condition is what decides —
     * between asking and acting, another attempt could take the row.
     *
     * <p>这一行还**能不能被新的尝试拿走**：它尚未成功，且既有的认领已早于租约——因为认领仍新鲜就意味着
     * 可能正有一次尝试在跑。
     *
     * <p>它问的与认领那条 SQL 条件问的是同一个问题，边界也与 {@link #derive} 判 {@code running} 的边界
     * 相同：两者恰好互补。重试入口靠它**带着理由拒绝**，但真正决定的是那条条件——问与做之间，另一个尝试
     * 可能已经把行拿走了。
     */
    public static boolean isClaimable(ConfirmationRequest row, Instant now) {
        if (row.getExecutedAt() != null) {
            return false;
        }
        Instant claimedAt = row.getExecutionClaimedAt();
        return claimedAt == null || now.toEpochMilli() - claimedAt.toEpochMilli() >= LEASE_MILLIS;
    }
}
