// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

/**
 * Result of a tool execution (mirrors the protocol's {@code ToolResult}, plus one field the protocol
 * does not carry).
 *
 * <p>The three the protocol names are {@code success}, {@code data} and {@code error}. The fourth,
 * {@code beforeSnapshot}, is this runtime's own: a tool that <em>updates</em> a row can hand back the
 * row as it was, and the engine stores it on the side effect — the frozen {@code traceItem}'s
 * {@code beforeSnapshot}. It never reaches the wire: what a caller sees is {@code data}, and the
 * snapshot is bookkeeping the ledger keeps. A tool that creates a row leaves it null, which is what
 * "there was no row before" honestly is.
 *
 * 一次工具执行的结果（**镜像**协议的 `ToolResult`，另加一个协议不带、本运行时自己的字段）。
 *
 * <p>协议点名的三个是 {@code success}、{@code data}、{@code error}。第四个 {@code beforeSnapshot} 是**本运行时
 * 自己的**：一个会**改行**的工具可以交回那一行**原先**的样子，而引擎把它存在副作用上 —— 也就是冻结
 * `traceItem` 的 `beforeSnapshot`。它**不上线**：调用方看到的是 `data`，而这份快照是账本自己留的记账。
 * 新建一行的工具把它留空 —— 那正是「之前没有行」。
 */
public record ToolResult(boolean success, Object data, String error, String beforeSnapshot) {

    /**
     * The three-argument shape, kept because it is what the callers that have no snapshot to hand back
     * know, and because a published artifact carries it.
     *
     * 三参那个形状 —— 为手上没有快照可交的调用方、也为**已发布**的 artifact 而保留。
     */
    public ToolResult(boolean success, Object data, String error) {
        this(success, data, error, null);
    }

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null, null);
    }

    /**
     * The same, carrying the row as it was before this call changed it — the frozen {@code traceItem}'s
     * {@code beforeSnapshot}, for tools that update rather than create.
     *
     * 同上，另带这一行被这次调用改**之前**的样子 —— 冻结 `traceItem` 的 `beforeSnapshot`，给**改行**而非新建的工具。
     */
    public static ToolResult ok(Object data, String beforeSnapshot) {
        return new ToolResult(true, data, null, beforeSnapshot);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error, null);
    }
}
