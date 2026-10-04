// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What discovery is allowed to add, and what it is not.
 *
 * <p>The MCP exchange itself is the official SDK's, and re-testing a transport would say nothing about
 * this repository. What is tested here is the part that is ours: how a tool a server advertises becomes
 * a tool this runtime governs.
 *
 * <p>Three things, and the middle one is the reason the module exists:
 *
 * <ol>
 *   <li>an advertised tool arrives named {@code mcp_<server>_<tool>}, so two servers cannot collide in
 *       one registry and an audit row says which server answered;
 *   <li>a tool the deployment never declared is <b>not exposed</b> — "discovered, therefore callable"
 *       would let any MCP server widen this runtime's authority without anybody deciding to;
 *   <li>what the server says about a call's outcome becomes what the caller reads, and a failure the
 *       server reports is a governed failure rather than a stack trace.
 * </ol>
 *
 * MCP 交换本身是官方 SDK 的，重测一条传输对本仓什么也说不了。这里测的是**属于我们的那一部分**：服务端宣称的
 * 工具如何成为本运行时治理的工具。
 *
 * <p>三件事，而中间那件正是本模块存在的理由：① 被宣称的工具以 `mcp_&lt;server&gt;_&lt;tool&gt;` 到达，两个服务端
 * 因此不会在同一个注册表里撞名、审计行也说得出是哪台服务端答的；② 部署方**从未声明**的工具**不暴露**——
 * 「被发现即可调用」等于让任何一台 MCP 服务端**不经任何人决定就放宽**本运行时的权限；③ 服务端对一次调用结果
 * 说的话，成为调用方读到的东西，而服务端报的失败是**受治理的失败**、不是堆栈。
 */
class McpDiscoveryAndPolicyTest {

    private static McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of()))
                .build();
    }

    private static McpSyncClient clientAdvertising(McpSchema.Tool... tools) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tools), null));
        return client;
    }

    private static McpToolPolicy policy() {
        return new McpToolPolicy("legacy-crm", Map.of(
                "list_open_tickets", McpToolPolicy.Grant.read(),
                "close_ticket", McpToolPolicy.Grant.compensatingWrite("ticket_closed")));
    }

    @Test
    void anAdvertisedToolArrivesNamedForItsServer() {
        McpSyncClient client = clientAdvertising(
                tool("list_open_tickets", "List the open tickets."),
                tool("close_ticket", "Close a ticket."));

        McpServerTools.Discovery found = McpServerTools.discover(client, policy());

        assertEquals(List.of("mcp_legacy_crm_list_open_tickets", "mcp_legacy_crm_close_ticket"),
                found.exposed().stream().map(AiTool::name).toList());
        assertEquals("List the open tickets.", found.exposed().get(0).description());
        assertTrue(found.withheld().isEmpty());
    }

    @Test
    void aToolNobodyDeclaredIsNotExposed() {
        McpSyncClient client = clientAdvertising(
                tool("list_open_tickets", "List the open tickets."),
                tool("drop_everything", "Deletes everything."));

        McpServerTools.Discovery found = McpServerTools.discover(client, policy());

        assertEquals(List.of("mcp_legacy_crm_list_open_tickets"),
                found.exposed().stream().map(AiTool::name).toList());
        // Named rather than dropped silently: an operator can see what a server grew and has to decide
        // about, which is the difference between "nothing happened" and "something was refused".
        assertEquals(List.of("drop_everything"), found.withheld());
    }

    @Test
    void theDeclarationDecidesWhetherAHumanIsAsked() {
        McpSyncClient client = clientAdvertising(
                tool("list_open_tickets", "List the open tickets."),
                tool("close_ticket", "Close a ticket."));

        McpServerTools.Discovery found = McpServerTools.discover(client, policy());

        AiTool read = found.exposed().get(0);
        AiTool write = found.exposed().get(1);
        assertEquals("R1", read.riskLevel());
        assertFalse(read.requiresConfirmation(), "a read needs no human");
        assertEquals("none", read.revokeClass());

        assertEquals("R3", write.riskLevel());
        assertTrue(write.requiresConfirmation(), "a write waits for a human");
        assertEquals("local_compensate", write.revokeClass());
        assertEquals("ticket_closed", write.resultType());
    }

    @Test
    void whatTheServerReturnedBecomesWhatTheCallerReads() {
        McpSyncClient client = clientAdvertising(tool("list_open_tickets", "List the open tickets."));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult("two tickets", false));

        AiTool read = McpServerTools.discover(client, policy()).exposed().get(0);
        ToolResult result = read.execute(Map.of(), null);

        assertTrue(result.success());
        assertEquals("two tickets", result.data());
    }

    @Test
    void aFailureTheServerReportsIsAFailureTheCallerReads() {
        McpSyncClient client = clientAdvertising(tool("close_ticket", "Close a ticket."));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult("no such ticket", true));

        AiTool write = McpServerTools.discover(client, policy()).exposed().get(0);
        ToolResult result = write.execute(Map.of(), null);

        assertFalse(result.success());
        assertEquals("no such ticket", result.error());
    }
}
