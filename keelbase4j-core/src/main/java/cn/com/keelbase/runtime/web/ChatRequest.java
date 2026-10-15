// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Body of {@code POST /ai/chat}.
 *
 * <p>{@code conversationId} is optional. The runtime issues the ids, so one it has never issued
 * starts a new conversation rather than being adopted — see {@code ConversationStore}.
 *
 * <p>{@code targetId} names the business object this turn is about. It is <b>generic on purpose</b>:
 * it used to be called {@code customerId}, and that one name was why a second module had nowhere of
 * its own to put a row id — it had to borrow a field named for CRM. Which object the id refers to is
 * the route's business rather than this body's; a route that needs to be sure reads the type it knows.
 *
 * <p>It is optional, and the console does not always send it: the console names the object it is
 * looking at inside the message ("当前客户「Acme」（ID 1）。…"), which is what a model would read. The
 * runtime resolves the reference either way — this field first, then the conversation's own
 * transcript — so a caller that has an id can state it, and one that does not is still understood
 * (see {@code ChatTurnService}).
 *
 * <p><b>{@code customerId} still binds.</b> The field was renamed for the reason above, and the old
 * name is kept as a JSON alias rather than dropped: the callers are not all in this repository — the
 * main repository's admin console posts the old name — and a rename that broke them would be a wire
 * change dressed up as a tidy-up.
 */
public record ChatRequest(String message, @JsonAlias("customerId") Long targetId,
                          String conversationId) {

    /**
     * The old name, still readable.
     *
     * @deprecated the slot is generic now — read {@link #targetId()}. Kept because callers written
     *             against it exist, and because removing it would turn a rename into a breakage.
     *
     * @deprecated 槽位现在是通用的 —— 读 {@link #targetId()}。留着是因为确实有照它写的调用方，也因为
     *             删掉它等于把一次改名变成一次破坏。
     */
    @Deprecated
    public Long customerId() {
        return targetId;
    }
}
