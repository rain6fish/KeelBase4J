// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.List;
import java.util.Set;

/**
 * AI Governance Protocol §2.3 — the closed vocabulary an audit row's {@code action} is drawn from.
 *
 * <p>The list is the contract's own, taken from the {@code action} enum of the current
 * {@code audit-payload} schema (v4 at contract v1.24.0). It is a <em>closed</em> vocabulary: a row
 * whose action is not on it does not describe something the protocol knows, and a runtime that writes
 * it anyway puts a word on the chain that no consumer can classify. {@link #require} is the guard a
 * writer calls before a row goes in; {@code AuditActionTest} pins this list to the vendored schema, so
 * a contract bump that adds or removes a word fails there rather than letting the two drift apart.
 *
 * <p>Which outcomes are legal for which action is a different vocabulary, owned by
 * {@code ai-audit-log-outcome-v3-vector.json}; this class says only which actions exist.
 *
 * AI 治理协议 §2.3 —— 审计行的 {@code action} 所取自的**闭词表**。
 *
 * <p>这张表就是契约自己的那张，取自当前 {@code audit-payload} schema（本仓 pin 的契约 v1.24.0 下为 v4）的
 * {@code action} 枚举。它是**闭**词表：一个不在表上的动作，不描述协议认识的任何东西；运行时照写不误，就是往链上
 * 放一个**没有任何消费方能归类**的词。{@link #require} 是写入方在行落库前调用的那道闸；`AuditActionTest`
 * 把这张表钉在 vendored schema 上，故契约增删一个词会在那里失败，而不是让两边悄悄漂开。
 *
 * <p>**哪种结局对哪种动作合法**是另一张词表，归 {@code ai-audit-log-outcome-v3-vector.json} 管；
 * 本类只说**有哪些动作**。
 */
public final class AuditAction {

    /**
     * The frozen vocabulary, in the contract's own order — the order a refusal reads back to a person.
     *
     * 冻结词表，按契约自己的顺序 —— 也就是一次拒绝回给读的人的那个顺序。
     */
    public static final List<String> VOCABULARY = List.of(
            "chat",
            "tool_call",
            "navigate",
            "error",
            "login",
            "plan",
            "analyze",
            "knowledge",
            "delegate",
            "tool_confirmation",
            "flow_node",
            "content_blocked",
            "effect_revoke",
            "audit_review",
            "claim_release");

    private static final Set<String> KNOWN = Set.copyOf(VOCABULARY);

    private AuditAction() {
    }

    /** Whether the word is on the contract's vocabulary. A null or blank action is never one. */
    public static boolean isKnown(String action) {
        return action != null && KNOWN.contains(action);
    }

    /**
     * The action, if it is on the vocabulary; otherwise a refusal that names the word and the list.
     *
     * <p>Returns the value rather than nothing so a writer can read {@code append(require(action), …)}
     * and cannot accidentally check one variable while writing another.
     *
     * 动作在词表上就原样返回；不在就抛一个**点名该词与整张表**的拒绝。
     *
     * <p>返回原值而**不是什么都不返回**，是为了让写入方写成 {@code append(require(action), …)} ——
     * 这样就不可能「校验了一个变量、写下的却是另一个」。
     */
    public static String require(String action) {
        if (!isKnown(action)) {
            throw new UnknownAuditActionException(action, VOCABULARY);
        }
        return action;
    }
}
