// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/** Body of {@code POST /ai/confirmations/{token}}. */
public record DecisionRequest(String decision) {
}
