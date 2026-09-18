// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/** Body of {@code POST /ai/chat}. */
public record ChatRequest(String message, Long customerId) {
}
