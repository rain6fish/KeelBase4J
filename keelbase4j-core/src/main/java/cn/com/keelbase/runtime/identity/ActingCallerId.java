// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import java.util.function.Supplier;

/**
 * Who is acting, from whichever of two places can say — in this order, and only this order.
 *
 * <p>A host's rows are reached two ways, and only one of them carries the caller where one would
 * expect. On the host's own request path its security context has it, because the filter chain
 * authenticated the request. On the runtime's tool path that context is empty, and the caller arrives
 * through {@link ActingCaller}, published by the tool that holds the runtime's {@code Principal}.
 * Asking only one of the two is how a host's seam produced a non-deterministic answer once already, so
 * both are asked, in that order.
 *
 * <p><b>What is lifted, and what is not.</b> The shape is the order and the arithmetic; which utility
 * reads the request's identity is the deployment's — {@code SecurityUtils} on one host,
 * {@code SecurityFrameworkUtils} on another — so it arrives as {@code requestSideUserId} rather than
 * being reached for here. A caller nobody can name yields {@code null}, and every layer that asks
 * treats that as "leave it alone".
 *
 * 「谁在行动」，由两处里说得出的那一处作答 —— **按这个次序，而且只有这个次序**。
 *
 * <p>一个宿主的行有两条到达路径，而其中只有一条在所期望的地方带着调用者。在宿主自己的请求路径上，它的安全
 * 上下文带着它（过滤器链认证了那个请求）；在运行时的工具路径上，那个上下文是**空的**，调用者经由
 * {@link ActingCaller} 到达 —— 由持有运行时 {@code Principal} 的那个工具发布。**只问其中一个**，正是某个
 * 宿主的接缝已经产生过一次**不确定答案**的方式；故两者都问、按下述顺序。
 *
 * <p><b>上提的是什么、不是什么。</b>形状 = **次序**与那段算术；而**读请求身份的是哪一个工具类**属于部署方 ——
 * 一个宿主是 `SecurityUtils`、另一个是 `SecurityFrameworkUtils` —— 所以它**作为参数**进来，而不是在这里
 * 去够。谁也说不出调用者时得到 {@code null}，而每一个问它的层都把它当作「**别动**」。
 */
public final class ActingCallerId {

    private ActingCallerId() {
    }

    /**
     * The acting caller's id, from the request's own identity when the deployment has one, else from
     * whatever published itself through {@link ActingCaller}, else {@code null}.
     *
     * <p>The request-side reading is a supplier because it may legitimately <em>throw</em> on the
     * runtime's tool path — a security context that was never filled — and that is not a failure here
     * but the reason the second place exists.
     *
     * 正在行动的调用者的 id：部署方有请求自己的身份时用它，否则用经 {@link ActingCaller} 发布自己的那个，
     * 都没有则 {@code null}。
     *
     * <p>请求侧那个读数做成 supplier，是因为它在运行时的工具路径上**可能合法地抛** —— 一个从未被填充的安全
     * 上下文 —— 而那在这里**不是失败**，正是**第二个去处存在的原因**。
     */
    public static Long current(Supplier<?> requestSideUserId) {
        try {
            Object fromRequest = requestSideUserId.get();
            if (fromRequest != null) {
                return parse(fromRequest);
            }
        } catch (RuntimeException noSecurityContext) {
            // Expected on the engine's tool path; the published caller below answers for it.
        }
        String acting = ActingCaller.current();
        return acting == null ? null : parse(acting);
    }

    /** An id, or {@code null} when the value is not one — a caller name is not a caller id. */
    private static Long parse(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
