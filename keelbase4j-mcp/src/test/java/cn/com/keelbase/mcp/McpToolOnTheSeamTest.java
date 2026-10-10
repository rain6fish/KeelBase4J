// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.KeelBase4JApplication;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole point of RY-15, on a running application: a tool discovered over MCP is governed by the
 * chain that governs the tools compiled into the runtime.
 *
 * <p>The server is a real MCP server — the official SDK's, serving over the protocol's HTTP transport —
 * on a port of its own rather than inside the application. That separation is deliberate: this module
 * discovers at startup, before a request-serving context of its own would be listening, and a deployment
 * reaches a server that is somebody else's process anyway.
 *
 * <p>Four things have to hold, and each is a different failure if it does not:
 *
 * <ol>
 *   <li><b>The declared tools are in the registry</b>, named for the server that answered.
 *   <li><b>The undeclared one is not.</b> The server advertises three; two are declared. Asking the
 *       registry for the third is how "discovered, therefore callable" would show up if it were true.
 *   <li><b>A write never reaches the server before a human approves.</b> The strongest assertion here:
 *       not "the answer says pending" but "the server was never asked".
 *   <li><b>A declared read runs</b>, through MCP, and what came back is what the caller reads.
 * </ol>
 *
 * RY-15 的全部要点，跑在一个起着的应用上：**经 MCP 发现的工具，受那条治理**编译进来的工具**的链**治理。
 *
 * <p>服务端是一台**真的** MCP 服务端——官方 SDK 的、经协议的 HTTP 传输服务——**在自己的端口**上，而不是装在
 * 应用里面。这个分离是刻意的：本模块在**启动时**发现，那时一个自己服务的上下文还没在听；而部署方够到的服务端
 * 本来就是**别人的进程**。
 *
 * <p>四件事必须同时成立，而每一件不成立都是不同的失败：① **已声明的工具在注册表里**、以答话的服务端命名；
 * ② **未声明的那个不在**——服务端宣称三个、声明了两个，而「去注册表里问第三个」正是「被发现即可调用」若为真
 * 会露出来的地方；③ **写在没有人类批准之前根本不到达服务端**——这是此处最强的断言：不是「回复说 pending」，
 * 而是「服务端**从未被问过**」；④ **已声明的读跑通了**，经 MCP，且回来的东西就是调用方读到的东西。
 */
@SpringBootTest(classes = KeelBase4JApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@Import(McpToolOnTheSeamTest.StubPlanner.class)
class McpToolOnTheSeamTest {

    /** Every tool call the MCP server was actually asked to serve. */
    static final List<String> SERVER_SAW = new CopyOnWriteArrayList<>();

    private static final Tomcat TOMCAT = new Tomcat();
    private static final int MCP_PORT = freePort();

    private static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of()))
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification serves(String name) {
        return new McpServerFeatures.SyncToolSpecification(tool(name, "Served by the MCP server."),
                (exchange, args) -> {
                    SERVER_SAW.add(name);
                    return new McpSchema.CallToolResult("served:" + name, false);
                });
    }

    static {
        try {
            var transport = HttpServletStreamableServerTransportProvider.builder()
                    .mcpEndpoint("/mcp").build();
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("legacy-crm", "1.0.0")
                    .tools(serves("list_open_tickets"), serves("close_ticket"), serves("drop_everything"))
                    .build();
            TOMCAT.setBaseDir(java.nio.file.Files.createTempDirectory("mcp-tomcat").toString());
            TOMCAT.setPort(MCP_PORT);
            TOMCAT.getConnector();
            var context = TOMCAT.addContext("", new java.io.File(".").getAbsolutePath());
            Tomcat.addServlet(context, "mcp", transport);
            context.addServletMappingDecoded("/mcp", "mcp");
            TOMCAT.start();
        } catch (Exception e) {
            throw new IllegalStateException("the MCP server the test needs did not start", e);
        }
    }

    @AfterAll
    static void stopServer() throws Exception {
        TOMCAT.stop();
        TOMCAT.destroy();
    }

    @DynamicPropertySource
    static void pointAtTheServer(DynamicPropertyRegistry registry) {
        registry.add("keelbase.mcp.server-url", () -> "http://localhost:" + MCP_PORT + "/mcp");
        registry.add("keelbase.mcp.server", () -> "legacy-crm");
        registry.add("keelbase.mcp.tools.list_open_tickets.risk-level", () -> "R1");
        registry.add("keelbase.mcp.tools.close_ticket.risk-level", () -> "R3");
        registry.add("keelbase.mcp.tools.close_ticket.revoke-class", () -> "local_compensate");
        registry.add("keelbase.mcp.tools.close_ticket.result-type", () -> "ticket_closed");
    }

    /** Proposes whichever MCP tool the message names — the seam a planner plugs into, stubbed. */
    @TestConfiguration
    static class StubPlanner {
        @Bean
        ToolCallPlanner stubPlanner() {
            return (message, context) -> {
                for (String candidate : List.of("list_open_tickets", "close_ticket", "drop_everything")) {
                    if (message.contains(candidate)) {
                        return Optional.of(new IntentPlan("mcp_legacy_crm_" + candidate, Map.of()));
                    }
                }
                return Optional.empty();
            };
        }
    }

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Autowired
    ToolRegistry registry;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ConfirmationRequestRepository confirmations;

    /** Drives one turn as a caller, with a delegation token minted the way the runtime's tests do. */
    private String chatAs(String userId, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        ResponseEntity<String> response = http.exchange("/ai/chat", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("message", message), headers), String.class);
        return response.getBody();
    }

    @Test
    void theDeclaredToolsAreRegisteredAndTheUndeclaredOneIsNot() {
        assertNotNull(registry.require("mcp_legacy_crm_list_open_tickets"));
        assertNotNull(registry.require("mcp_legacy_crm_close_ticket"));
        // The server advertises this one. Nothing declared it, so it is not a tool here — which is the
        // whole difference between discovery and authority, and the registry refusing to name it is
        // what that difference looks like from the inside.
        assertThrows(RuntimeException.class,
                () -> registry.require("mcp_legacy_crm_drop_everything"),
                "an undeclared MCP tool was exposed");
    }

    @Test
    void anApprovedReadRunsOverMcp() {
        SERVER_SAW.clear();
        chatAs("alice", "please list_open_tickets");

        // The read ran, and the evidence is that the server was asked — which is what the outcome word
        // on the answer used to stand for. The answer carries no outcome now: it is the frozen
        // `chat-response` (JV-52 片 2).
        //
        // 读跑了，而证据是**服务端被问过** —— 那正是过去答案上那个结果词所代表的东西。答案现在不带结果：
        // 它就是冻结的 `chat-response`（JV-52 片 2）。
        assertEquals(List.of("list_open_tickets"), SERVER_SAW);
    }

    @Test
    void aWriteDoesNotReachTheServerBeforeAHumanApproves() {
        SERVER_SAW.clear();
        String body = chatAs("alice", "please close_ticket");

        // Both halves, and neither alone is the claim: a row says the write is waiting, and the server
        // was never asked. The answer cannot say "pending" any more — the object has no such field.
        //
        // 两半都要，而**任何一半单独都不是**这句话：**有一行**说写在等，**且**服务端从没被问过。答案已经不能
        // 说「pending」了 —— 那个对象没有这个字段。
        assertTrue(confirmations.findByOperatorIdAndModeOrderByCreatedAtDesc(
                        "alice", ConfirmationMode.IMMEDIATE).stream()
                .anyMatch(row -> ConfirmationLifecycle.PENDING.equals(row.getStatus())), body);
        assertTrue(SERVER_SAW.isEmpty(), "the write reached the MCP server before a human approved: " + SERVER_SAW);
    }
}
