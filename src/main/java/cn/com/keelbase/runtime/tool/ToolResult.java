// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

/** Result of a tool execution (mirrors the protocol's {@code ToolResult}). */
public record ToolResult(boolean success, Object data, String error) {

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error);
    }
}
