// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import cn.com.keelbase.runtime.tool.AiTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovery, normalization, and the refusal that follows from having no declaration.
 *
 * <p>What a server advertises and what this deployment exposes are two different sets, and the gap
 * between them is not silent: {@link Discovery} hands back both, so an operator can see a tool the
 * server grew that nobody has declared yet. Exposing it instead — "discovered, therefore callable" —
 * would make every MCP server a way to widen this runtime's authority without anybody deciding to.
 *
 * 服务端自称有什么、与本部署实际暴露什么，是**两个不同的集合**，而它们之间的差额**不是静默的**：
 * {@link Discovery} 把两边都交回来，运维因此看得见「服务端新长出一个、而没人声明过」的工具。反过来做——
 * 「被发现即可调用」——等于让**每一台 MCP 服务端**都成为一条**不经任何人决定就放宽本运行时权限**的路。
 */
public final class McpServerTools {

    private McpServerTools() {
    }

    /** Opens a Streamable HTTP session to a server. The caller owns closing it. */
    public static McpSyncClient connect(String serverUrl) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder(serverUrl).build()).build();
    }

    /**
     * What discovery found: the tools this policy exposes, and the ones it does not.
     *
     * 发现的结果：本策略暴露的工具，以及**不**暴露的那些。
     */
    public record Discovery(List<AiTool> exposed, List<String> withheld) {
    }

    /** Initializes the session, lists the server's tools, and keeps only the declared ones. */
    public static Discovery discover(McpSyncClient client, McpToolPolicy policy) {
        client.initialize();
        McpSchema.ListToolsResult listed = client.listTools();
        List<McpSchema.Tool> tools = listed == null || listed.tools() == null ? List.of() : listed.tools();

        List<AiTool> exposed = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        for (McpSchema.Tool tool : tools) {
            var grant = policy.grantFor(tool.name());
            if (grant.isEmpty()) {
                // Named, so the operator's log says which tool arrived undeclared — the difference
                // between "nothing happened" and "something was refused" is the whole value here.
                withheld.add(tool.name());
                continue;
            }
            exposed.add(new McpToolAdapter(client, policy.server(), tool, grant.get()));
        }
        return new Discovery(List.copyOf(exposed), List.copyOf(withheld));
    }
}
