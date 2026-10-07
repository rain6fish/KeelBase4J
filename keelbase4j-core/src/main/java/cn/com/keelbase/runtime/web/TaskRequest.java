// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/**
 * Body of {@code POST /ai/task}: the words, and nothing else.
 *
 * <p>Deliberately not {@link ChatRequest}. That one carries a customer id and a conversation id, and
 * this path honours neither: the model is given the message and the tools, and resolves whatever the
 * message refers to by reading it — exactly as a caller of the single-shot path has to write it.
 * Accepting fields that are then ignored is how a caller comes to believe they were used.
 *
 * <p>{@code POST /ai/task} 的请求体：只有那句话。
 *
 * <p>刻意**不用** {@link ChatRequest}。那个带着客户 id 与会话 id，而本路径**两者都不认**：模型拿到的是
 * **消息与工具**，消息提到的东西由它**读**出来 —— 正如单发路径的调用方也必须这么写。收下随后被忽略的字段，
 * 正是**调用方开始以为它们生效了**的原因。
 */
public record TaskRequest(String message) {
}
