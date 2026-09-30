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
 *
 * <p>一行是哪一种确认——冻结的 {@code my-confirmation-item.mode} 词汇。
 *
 * <p>**不由风险级推导**：两者不是同义词。部署方可以把一个非 R4 的工具升档为审批，而 mode 决定的
 * 恰恰是「**谁可以回答这一行**」——操作者本人（{@link #IMMEDIATE}）还是第二个人（{@link #APPROVAL}）。
 *
 * <p>契约的枚举里还有 {@code run}（一次裁决覆盖一整批）。本运行时**产不出它**，故不在此列出：
 * 一个产不出的常量只会诱使有人去服务它。
 */
public final class ConfirmationMode {

    /** The operator answers their own confirmation (R3). */
    public static final String IMMEDIATE = "immediate";

    /** A second person answers it (R4); the write then runs as the operator (ADR-0018). */
    public static final String APPROVAL = "approval";

    private ConfirmationMode() {
    }
}
