// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/**
 * Body of {@code POST /ai/chat}.
 *
 * <p>{@code conversationId} is optional. The runtime issues the ids, so one it has never issued
 * starts a new conversation rather than being adopted — see {@code ConversationStore}.
 */
public record ChatRequest(String message, Long customerId, String conversationId) {
}
