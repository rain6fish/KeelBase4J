// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.runtime.identity.AuthenticatedSubject;

/**
 * The answer to "who is this request": either the subject that was proved, or why nothing was.
 *
 * <p>It exists so the refusal can reach the entry point with its reason intact. The older shape —
 * {@link CallerAuthenticator#authenticate} returning {@code null} — cannot carry a reason, which is
 * why every authentication failure used to read the same on the wire.
 *
 * <p>Exactly one of {@code subject} and {@code failure} is set.
 *
 * <p>「这条请求是谁」的答案：要么是已证明的主体，要么是**为什么什么都没证明**。
 *
 * <p>它存在的意义，是让拒绝带着理由到达入口。旧的形状 —— {@link CallerAuthenticator#authenticate}
 * 返回 `null` —— 带不了理由，这正是此前每一种认证失败在 wire 上读起来都一样的缘故。
 *
 * <p>`subject` 与 `failure` 恰有一个有值。
 */
public record CallerAuthentication(AuthenticatedSubject subject, CallerAuthenticationFailure failure) {

    public static CallerAuthentication authenticated(AuthenticatedSubject subject) {
        return new CallerAuthentication(subject, null);
    }

    public static CallerAuthentication refused(CallerAuthenticationFailure failure) {
        return new CallerAuthentication(null, failure);
    }

    public boolean authenticated() {
        return subject != null;
    }
}
