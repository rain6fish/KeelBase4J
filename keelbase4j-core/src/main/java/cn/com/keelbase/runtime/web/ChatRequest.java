// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/**
 * Body of {@code POST /ai/chat}.
 *
 * <p>{@code conversationId} is optional. The runtime issues the ids, so one it has never issued
 * starts a new conversation rather than being adopted — see {@code ConversationStore}.
 *
 * <p>{@code customerId} is optional as well, and no frontend sends it: the console names the customer
 * it is looking at inside the message ("当前客户「Acme」（ID 1）。…"), which is what a model would read.
 * The runtime resolves the reference either way — this field first, then the conversation's own
 * transcript — so a caller that has an id can state it, and one that does not is still understood
 * (see {@code ChatTurnService}).
 */
public record ChatRequest(String message, Long customerId, String conversationId) {
}
