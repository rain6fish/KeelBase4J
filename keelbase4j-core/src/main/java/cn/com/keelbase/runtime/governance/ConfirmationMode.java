// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

/**
 * Which confirmation a row is — the frozen {@code my-confirmation-item.mode} vocabulary.
 *
 * <p>Not derived from the risk level: the two are not synonyms. A deployment may promote a tool to
 * approval without it being R4, and the mode is what decides <em>who may answer the row</em> — the
 * operator themselves ({@link #IMMEDIATE}) or a second person ({@link #APPROVAL}).
 *
 * <p>The contract's enum also has {@code run} (one decision covering a batch). This runtime does not
 * produce it, so it is not listed here: a constant nothing can produce would invite serving it.
 */
public final class ConfirmationMode {

    /** The operator answers their own confirmation (R3). */
    public static final String IMMEDIATE = "immediate";

    /** A second person answers it (R4); the write then runs as the operator (ADR-0018). */
    public static final String APPROVAL = "approval";

    private ConfirmationMode() {
    }
}
