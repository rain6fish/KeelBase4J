// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/**
 * Body of {@code POST /ai/tool-effects/claims/{id}/release}.
 *
 * <p>{@code note} says why the hold was given up on and is required — the row exists to answer that,
 * and a release nobody can explain is indistinguishable from the hold being forgotten. {@code
 * evidenceRef} is an optional pointer to what the operator looked at.
 *
 * <p>{@code POST /ai/tool-effects/claims/{id}/release} 的请求体。
 *
 * <p>`note` 说明**为什么**放弃这份占位，且必填 —— 这一行存在的意义就是回答它，而一次没人说得清的释放，
 * 与「把占位忘了」分不开。`evidenceRef` 是可选指针，指向运维看过的东西。
 */
public record ReleaseClaimRequest(String note, String evidenceRef) {
}
