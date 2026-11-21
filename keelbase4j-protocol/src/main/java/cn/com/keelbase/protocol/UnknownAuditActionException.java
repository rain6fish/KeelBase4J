// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.Collection;

/**
 * An audit row was offered an {@code action} that is not on the contract's vocabulary.
 *
 * <p>Its own type, rather than a bare {@link IllegalArgumentException}, so a caller that wants to tell
 * "this row used a word the protocol does not know" apart from every other argument problem can catch
 * exactly that. {@link #action()} carries the word that was refused, which is also what the message
 * names.
 *
 * 一条审计行被给了一个**不在契约词表上**的 {@code action}。
 *
 * <p>做成**自己的类型**、而不是一个裸的 {@link IllegalArgumentException}：想让「这一行用了协议不认识的词」
 * 与其它参数问题分开处理的调用方，可以**恰好**接住这一种。{@link #action()} 带着被拒的那个词 ——
 * 消息里点名的也是它。
 */
public class UnknownAuditActionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String action;

    public UnknownAuditActionException(String action, Collection<String> vocabulary) {
        super("unknown audit action: " + (action == null ? "null" : "\"" + action + "\"")
                + "; the contract's vocabulary is " + String.join(", ", vocabulary));
        this.action = action;
    }

    /** The word that was refused — null when the caller passed null. / 被拒的那个词；调用方传 null 时为 null。 */
    public String action() {
        return action;
    }
}
