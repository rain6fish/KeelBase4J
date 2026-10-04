// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What a deployment says about one MCP server: where it is, what to call it, and which of the tools it
 * advertises this deployment is willing to have called.
 *
 * <pre>
 * keelbase:
 *   mcp:
 *     server-url: http://localhost:8931/mcp
 *     server: legacy-crm
 *     tools:
 *       list_open_tickets:   { risk-level: R1 }
 *       close_ticket:        { risk-level: R3, revoke-class: local_compensate }
 * </pre>
 *
 * <p>A tool the server advertises and this table does not name is not exposed at all — see
 * {@link McpToolPolicy}, which is where that decision lives.
 *
 * 部署方对一台 MCP 服务端说的话：它在哪、叫它什么，以及它宣称的工具里哪些本部署愿意让其被调用。
 *
 * <p>服务端宣称、而这表里没点名的工具，根本不暴露——那个决定在 {@link McpToolPolicy} 里。
 */
@ConfigurationProperties("keelbase.mcp")
public class McpProperties {

    private String serverUrl;
    private String server = "mcp";
    private Map<String, Grant> tools = new LinkedHashMap<>();

    /** One declared tool. The defaults describe a read, so a write has to say something more. */
    public static class Grant {
        private String riskLevel = "R1";
        private String revokeClass = "none";
        private String resultType;

        public String getRiskLevel() {
            return riskLevel;
        }

        public void setRiskLevel(String riskLevel) {
            this.riskLevel = riskLevel;
        }

        public String getRevokeClass() {
            return revokeClass;
        }

        public void setRevokeClass(String revokeClass) {
            this.revokeClass = revokeClass;
        }

        public String getResultType() {
            return resultType;
        }

        public void setResultType(String resultType) {
            this.resultType = resultType;
        }
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public Map<String, Grant> getTools() {
        return tools;
    }

    public void setTools(Map<String, Grant> tools) {
        this.tools = tools;
    }

    /** The declared table, in the shape the policy takes. */
    public McpToolPolicy policy() {
        Map<String, McpToolPolicy.Grant> grants = new LinkedHashMap<>();
        tools.forEach((tool, g) -> grants.put(tool,
                new McpToolPolicy.Grant(g.getRiskLevel(), g.getRevokeClass(), g.getResultType())));
        return new McpToolPolicy(server, grants);
    }
}
