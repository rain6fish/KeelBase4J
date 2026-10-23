// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

/**
 * The trace id the host is currently executing under, carried on the thread that writes audit rows.
 *
 * <p>The runtime has no tracing library and must not grow one: it only needs the value the host already
 * has. A host sets it at the seam it owns — a request filter, or the tool callback assembled for an
 * agent — and the audit sink reads it while appending the row. Null means the host had no trace to
 * give, which is recorded as null rather than guessed.
 *
 * 宿主当前执行所在的 trace id，挂在线程上、由写审计行的路径读取。
 *
 * <p>运行时不带追踪库、也不该长出一个：它只需要宿主**已经有**的那个值。宿主在自己拥有的缝上设置它 ——
 * 请求过滤器，或为 agent 装配的工具回调 —— 审计写入处读取它。null 表示宿主没有可给的 trace，记 null、不猜。
 */
public final class ExecutionTraceHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ExecutionTraceHolder() {
    }

    /** Sets the trace id; a blank value clears it rather than storing an empty string. / 设置 trace id；空白值按清除处理，不存空串。 */
    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(traceId);
        }
    }

    /** The trace id on this thread, or null when the host did not set one. / 本线程的 trace id；宿主未设置时为 null。 */
    public static String current() {
        return CURRENT.get();
    }

    /** Clears the value; callers that set it must clear it in a finally block. / 清除该值；设置它的调用方必须在 finally 中清除。 */
    public static void clear() {
        CURRENT.remove();
    }
}
