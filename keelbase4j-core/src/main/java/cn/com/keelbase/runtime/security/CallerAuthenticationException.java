// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A 401 raised after the token verified, carrying the same actionable failure record the entry point
 * uses.
 *
 * <p>The entry point cannot answer this one: the request <em>was</em> authenticated, and the refusal
 * happens later, when the verified subject is mapped to a local identity. Without this type the
 * advice would render the plain {@link ResponseStatusException} with the generic 401 guidance, which
 * would contradict the specific message and tell a caller holding a valid token to "attach a valid
 * token".
 *
 * <p>令牌验过之后才发生的 401，携带与入口同一份可行动的失败记录。
 *
 * <p>入口答不了这一条：请求**确实**认证过了，拒绝发生在更后面 —— 把已验证的主体映射成本地身份时。
 * 没有这个类型，advice 会把普通的 {@link ResponseStatusException} 渲染成泛泛的 401 指引，与具体的正文
 * 自相矛盾，还会让一个拿着有效令牌的调用方去「附上有效令牌」。
 */
public class CallerAuthenticationException extends ResponseStatusException {

    private final CallerAuthenticationFailure failure;

    public CallerAuthenticationException(CallerAuthenticationFailure failure) {
        super(HttpStatus.UNAUTHORIZED, failure.what());
        this.failure = failure;
    }

    public CallerAuthenticationFailure failure() {
        return failure;
    }
}
