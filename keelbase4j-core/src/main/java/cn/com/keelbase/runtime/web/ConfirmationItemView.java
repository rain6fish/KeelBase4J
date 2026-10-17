// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One confirmation record in the frozen {@code my-confirmation-item} shape — the single projection
 * both the operator's own list and the audit reader's queue answer with.
 *
 * <p>It is one class rather than two methods because the whole point of JV-63 B6 is that the read-only
 * face reuses the existing projection instead of inventing a second, wider one: what an audit reader
 * sees is exactly what the operator sees about their own row, with nothing added and nothing dropped.
 *
 * <p>一条确认记录，按冻结的 {@code my-confirmation-item} 形状作答 —— 操作者自己的列表与审计读者的队列
 * **共用同一份投影**。
 *
 * <p>做成一个类而不是两份方法，是因为 JV-63 B6 的全部要点就是：只读面**复用既有投影**，而不是另造一份更宽的。
 * 审计读者看到的东西，与操作者看自己那一行时看到的**一模一样**，不加也不减。
 */
final class ConfirmationItemView {

    private ConfirmationItemView() {
    }

    /**
     * Fields this runtime has no concept for are present and null — a {@code summary} needs the
     * presentation layer the reference has and this runtime does not, an {@code impact} preview
     * likewise, and a {@code run} batch belongs to a mode this runtime never produces. {@code expiresAt}
     * is the exception: the contract gives it to pending rows and omits it on terminal ones, and it is
     * not nullable there, so it is left out rather than sent as null.
     *
     * <p>{@code mode} is read off the row rather than written as a constant, so a list that ever
     * widens follows the row instead of lying about it.
     *
     * <p>本运行时没有概念的字段**在场且为 null** —— `summary` 需要参照实现有、而本运行时没有的呈现层，
     * `impact` 预览同理，`run` 批次属于本运行时从不产出的模式。`expiresAt` 是例外：契约把它给 pending 行、
     * 在终态行上**省略**，且那里不可空，故**不放**它、而不是送 null。
     *
     * <p>`mode` **从行上读**、不写死常量，故一份将来变宽的列表会跟着行走，而不是撒谎。
     */
    static Map<String, Object> item(ConfirmationRequest row, Instant now, long offlineTtlMillis,
                                    ToolRegistry tools) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("token", row.getToken());
        item.put("toolName", row.getToolName());
        item.put("summary", null);
        item.put("arguments", parseArgs(row.getArgsJson()));
        item.put("mode", row.getMode());
        item.put("riskLevel", row.getRiskLevel());
        item.put("status", row.getStatus());
        item.put("impact", null);
        item.put("revokeClass", revokeClassOf(row, tools));
        item.put("run", null);
        item.put("createdAt", row.getCreatedAt().toString());
        item.put("decidedAt", row.getDecidedAt() == null ? null : row.getDecidedAt().toString());
        if (ConfirmationLifecycle.PENDING.equals(row.getStatus())) {
            item.put("expiresAt", row.getCreatedAt().plusMillis(offlineTtlMillis).toString());
        }
        item.put("executionState", ExecutionAxis.derive(row, now));
        item.put("executedAt", row.getExecutedAt() == null ? null : row.getExecutedAt().toString());
        item.put("executionError", row.getExecutionError());
        return item;
    }

    /**
     * The tool's own revoke class, or null when this deployment no longer declares that tool — the
     * contract allows either, and guessing on a row whose tool is gone is not an option.
     *
     * 工具自己声明的撤销类别；本部署已不再声明该工具时为 null —— 契约两者都允许，而在工具已消失的行上猜
     * 一个不是选项。
     */
    private static String revokeClassOf(ConfirmationRequest row, ToolRegistry tools) {
        return tools.all().stream()
                .filter(tool -> tool.name().equals(row.getToolName()))
                .map(AiTool::revokeClass)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String argsJson) {
        return (Map<String, Object>) Json.parse(argsJson);
    }
}
