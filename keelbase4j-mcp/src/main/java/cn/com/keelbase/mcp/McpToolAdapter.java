// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/**
 * One tool an MCP server advertises, as a tool this runtime governs.
 *
 * <p>It is an {@link AiTool} and nothing else, which is the point: the engine's chain — the caller's
 * permissions, the confirmation a write waits for, the audit entry, the revoke path — applies to it
 * exactly as it applies to a tool that was compiled in. Discovery is the only thing MCP adds.
 *
 * <p><b>The name is ours, not the server's.</b> {@code mcp_<server>_<tool>} keeps two servers' tools
 * apart in one registry, and keeps them recognisable in an audit row. The main line's runtimes name
 * them the same way, so a decision recorded on one is legible on the other.
 *
 * <p><b>The description is the server's, and that is safe because it is only a description.</b> It
 * reaches the planner, which may propose a call — and a proposal is all it can be: whether the call
 * runs is decided downstream from the declaration this adapter carries, never from what the server
 * says about itself. What the server says must not include a risk level, because MCP has none and a
 * level it invented would be a claim about authority it does not have.
 *
 * 它就是一个 {@link AiTool}、别的什么都不是，而这正是要点：引擎那条链——调用方的权限、写要等的确认、审计行、
 * 撤销路径——对它施加的方式，与对一个**编译进来**的工具完全相同。发现是 MCP 唯一加上的东西。
 *
 * <p><b>名字是我们的，不是服务端的。</b>`mcp_&lt;server&gt;_&lt;tool&gt;` 让两个服务端的工具在同一个注册表里分得开、
 * 也让它们在审计行里认得出来。主线的各个运行时用的是同一套命名，故一端记下的决定在另一端读得懂。
 *
 * <p><b>描述是服务端的，而这安全，因为它只是**描述**。</b>它到达规划器，规划器可以据此提议一次调用——而提议
 * 也只能是提议：调用**跑不跑**由下游依据**本适配器携带的声明**决定，永远不由服务端**自称**决定。服务端所说
 * 的东西里不该有风险级，因为 MCP 没有它，而它若自己发明一个，那是在主张它并不拥有的权限。
 */
public final class McpToolAdapter implements AiTool {

    private final McpSyncClient client;
    private final String server;
    private final McpSchema.Tool tool;
    private final McpToolPolicy.Grant grant;

    McpToolAdapter(McpSyncClient client, String server, McpSchema.Tool tool, McpToolPolicy.Grant grant) {
        this.client = client;
        this.server = server;
        this.tool = tool;
        this.grant = grant;
    }

    @Override
    public String name() {
        return "mcp_" + segment(server) + "_" + segment(tool.name());
    }

    /**
     * A stable identifier, because the name is parsed rather than only displayed.
     *
     * <p>Server names are written the way people write them — `legacy-crm` — and tool names come from
     * somebody else's server, so neither can be trusted to the character set the runtime's own tool-name
     * parser accepts (`[A-Za-z0-9_]`). A hyphen would produce a name that reads correctly in a catalogue
     * and fails to parse in an audit row, which is the kind of failure that is discovered long after it
     * matters.
     *
     * 名字是**被解析**的、不只是被显示的。服务端名按人的写法来（`legacy-crm`），工具名来自**别人的**服务端，
     * 两者都不能假定符合运行时自己的工具名解析器所接受的那个字符集（`[A-Za-z0-9_]`）。一个连字符会产生一个
     * 「在目录里读起来对、在审计行里解析不了」的名字——而这类失败总是在它已经要紧之后才被发现。
     */
    static String segment(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "_");
    }

    @Override
    public String description() {
        return tool.description() == null ? "" : tool.description();
    }

    @Override
    public String riskLevel() {
        return grant.riskLevel();
    }

    @Override
    public String resultType() {
        return grant.resultType();
    }

    @Override
    public String revokeClass() {
        return grant.revokeClass();
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(tool.name(), args == null ? Map.of() : args));
        String text = McpToolAdapter.text(result);
        // The server's own failure flag, not an exception: MCP reports a refused call as a result, and
        // a governed failure is what the caller should read rather than a stack trace.
        return Boolean.TRUE.equals(result.isError()) ? ToolResult.fail(text) : ToolResult.ok(text);
    }

    /** The text of every text content the server returned, joined — what a reply can be written from. */
    static String text(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (McpSchema.Content part : result.content()) {
            if (part instanceof McpSchema.TextContent text) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(text.text());
            }
        }
        return out.toString();
    }
}
