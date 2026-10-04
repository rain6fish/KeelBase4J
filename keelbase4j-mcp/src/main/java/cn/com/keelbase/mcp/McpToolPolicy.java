// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import java.util.Map;
import java.util.Optional;

/**
 * How a discovered MCP tool is governed — the whole of what discovery is allowed to add.
 *
 * <p><b>MCP says nothing about risk, and that is the point.</b> A tool arrives with a name, a
 * description and an input schema; whether calling it needs a human, and what a caller must hold to be
 * allowed, are this deployment's decisions and nobody else's. So they are declared here, per tool, and
 * <b>a tool nobody declared is not exposed at all</b> — fail-closed, the same direction the runtime
 * takes everywhere else. A server that grows a new tool does not thereby gain the ability to have it
 * called: it gains a line in the operator's log.
 *
 * <p>The declaration is deliberately a table rather than a convention. Deriving a risk level from a
 * name ("it says delete, so R3") reads well until it is wrong, and when it is wrong it is wrong
 * silently, on the path where a write runs.
 *
 * MCP 对风险的沉默正是要点所在。一个工具带的是名字、描述与入参 schema；调用它要不要人、调用方必须持有什么，
 * 是**本部署**的决定、不是别人的。所以这些**逐条声明在这里**，而**没人声明过的工具根本不暴露**——失败即拒绝，
 * 与运行时在别处取的方向一致。服务端多出一个工具，并不会因此获得「它可被调用」的能力：它获得的是运维日志里的
 * 一行。
 *
 * <p>声明刻意做成**表**而不是**约定**。从名字推风险级（「它叫 delete，所以是 R3」）读起来很顺，直到它错；
 * 而它错的时候是**静默地**错，且错在**写会执行的那条路**上。
 */
public final class McpToolPolicy {

    /**
     * What the deployment allows for one discovered tool: the risk level that decides whether a human
     * must confirm, and the revoke class the ledger records (a write that can be compensated says so;
     * a read says {@code none}).
     *
     * 部署方对某一个被发现工具的允许：决定要不要人确认的**风险级**，与账本记下的**撤销档**。
     */
    public record Grant(String riskLevel, String revokeClass, String resultType) {

        /** A read: no confirmation, nothing to revoke. */
        public static Grant read() {
            return new Grant("R1", "none", null);
        }

        /** A write that a human must confirm and whose effect can be compensated locally. */
        public static Grant compensatingWrite(String resultType) {
            return new Grant("R3", "local_compensate", resultType);
        }
    }

    private final String server;
    private final Map<String, Grant> grants;

    public McpToolPolicy(String server, Map<String, Grant> grants) {
        this.server = server;
        this.grants = Map.copyOf(grants);
    }

    /** The server this policy is for — the middle segment of every name it hands out. */
    public String server() {
        return server;
    }

    /** The grant for a discovered tool, or empty when the deployment never declared one. */
    public Optional<Grant> grantFor(String tool) {
        return Optional.ofNullable(grants.get(tool));
    }

    /** Every tool this policy exposes, for an operator to diff against what a server advertises. */
    public Map<String, Grant> declared() {
        return grants;
    }
}
