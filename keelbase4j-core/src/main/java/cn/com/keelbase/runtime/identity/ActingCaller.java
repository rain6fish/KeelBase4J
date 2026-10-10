// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import java.util.function.Supplier;

/**
 * Who is acting, for the stretch of code an AI tool runs — the caller the web layer cannot supply.
 *
 * <p><b>Lifted, not invented.</b> Two hosts that embed this runtime arrived at this class
 * independently, and their copies are the same text: the runtime's tool path runs outside the request's
 * security filter chain, so the framework's own "current user" throws or answers nothing there. The
 * caller is not missing — the tool holds it, as the {@link Principal} the engine hands to
 * {@code execute} — so it is published here for the length of that call and read by whatever needs it.
 * Reaching for the caller through the security context instead makes the answer depend on which path
 * the decision ran on.
 *
 * <p><b>Deliberately not a general "current user".</b> It is set by code that already has a Principal
 * and read by code running synchronously beneath it — no executor, no queue, no async boundary between
 * the two, which is what keeps a thread-local the right shape here rather than the wrong one. It is
 * restored in a {@code finally}, so it cannot outlive the call that owns it.
 *
 * <p><b>What a host still owns.</b> Publishing the caller is the tool's, and reading the request's own
 * identity is the host's — {@link ActingCallerId} takes that reading as an argument rather than
 * reaching for a framework's utility, because which utility that is belongs to the deployment.
 *
 * 正在行动的是谁——**AI 工具跑的那一段**，web 层供不出的那个调用者。
 *
 * <p><b>这是上提来的、不是发明的。</b>两个嵌入本运行时的宿主**各自独立**走到了这个类，而两份副本就是**同一段
 * 文字**：运行时的**工具路径**跑在请求的安全过滤器链**之外**，所以框架自己那个「当前用户」在那儿要么抛、要么
 * 什么也答不出。那里**不是没有调用者**——调用者在工具手里，就是引擎交给 `execute` 的那个 {@link Principal}
 * ——所以在那次调用的时长内把它发布在这里，谁要用谁读。反过来**从安全上下文去够调用者**，会让答案取决于
 * **判决跑在哪条路径上**。
 *
 * <p><b>刻意不做成通用的「当前用户」。</b>设它的是**已经有 Principal 的代码**，读它的是**同步跑在它下面**的
 * 代码——两者之间没有执行器、没有队列、没有异步边界，这正是「此处线程局部是对的形状、而非错的形状」的来处。
 * 它在 `finally` 里还原，故不会活过拥有它的那次调用。
 *
 * <p><b>宿主仍然拥有的那部分。</b>**发布**调用者是工具的活，而**读请求自己的身份**是宿主的活 ——
 * {@link ActingCallerId} 把那个读数**作为参数**收进来，而不是去够某个框架的工具类：那个工具类是哪一个，
 * 属于部署方。
 */
public final class ActingCaller {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActingCaller() {
    }

    /** Runs {@code body} with {@code userId} as the acting caller, restoring what was there before. */
    public static <T> T as(String userId, Supplier<T> body) {
        String previous = CURRENT.get();
        CURRENT.set(userId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The acting caller for this thread, or {@code null} when nothing published one. */
    public static String current() {
        return CURRENT.get();
    }
}
