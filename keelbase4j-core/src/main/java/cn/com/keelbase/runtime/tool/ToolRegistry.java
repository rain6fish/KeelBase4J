// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registry of available tools, discovered from the Spring context.
 *
 * <p><b>A governed write that names no result type is refused registration, not registered as a
 * read.</b> Such a tool cannot be offered to a model and cannot be executed: an approved call would
 * otherwise land a write with no side-effect row to audit or revoke. The refusal is kept so
 * {@code keelbase/status} can name it and the startup log can say why; a caller that asks for it by
 * name gets the same explanation instead of "unknown tool".
 *
 * <p>Reads are unaffected: a tool with no result type at R0-R2 is registered normally.
 *
 * 从 Spring 上下文发现并注册可用的工具。
 *
 * <p><b>一个没有点名结果类型的受治理写，会被**拒绝注册**，而不是登记成只读。</b>这样的工具不会被交给
 * 模型、也无法执行：否则一次被批准的调用会落下一次写，却没有可供审计或撤销的副作用行。拒绝会被记下来，
 * 好让 {@code keelbase/status} 点名、启动日志说清原因；按名字来取它的调用方会拿到同一份解释，而不是
 * 「未知工具」。
 *
 * <p>读工具不受影响：R0–R2 且没有结果类型的工具照常注册。
 */
@Component
public class ToolRegistry {

    /**
     * Where a tool came from, which decides the two different questions the registry answers.
     *
     * <p>A **deployment** tool is one the host wrote as a Spring `AiTool` bean: it belongs to the
     * deployment's own catalogue, so it may be offered to a model by the deployment's planners. A
     * **host-assembled** tool is one a host registers while it assembles a turn (an MCP server's tool,
     * a SKILL's shell): it belongs to that one agent's binding, and offering it through the deployment
     * catalogue would hand every agent every other agent's tools. The engine, the policy store, the
     * inventory and the confirmation views read {@link #all()}; the two model faces read
     * {@link #catalogue()}.
     *
     * <p>工具来自哪里，决定了注册表要回答的两个不同问题。**部署**工具是宿主写成 Spring `AiTool` bean 的那些：
     * 它们属于部署自己的目录，故部署的规划器可以把它交给模型。**宿主装配**工具是宿主在装配一轮会话时注册的
     * （某个 MCP 服务的工具、某个 SKILL 的 shell）：它属于那**一个** agent 的绑定，若从部署目录交出去，等于
     * 把每个 agent 的工具发给所有 agent。引擎、策略库、清单与确认视图读 {@link #all()}；两个模型面读
     * {@link #catalogue()}。
     */
    public enum Origin {
        DEPLOYMENT,
        HOST_ASSEMBLED
    }

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final Map<String, Origin> origins = new LinkedHashMap<>();
    private final Map<String, AiTool> refused = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> beans) {
        for (AiTool tool : beans) {
            register(tool, Origin.DEPLOYMENT);
        }
    }

    /**
     * Register one tool, or refuse it with a nameable reason. Kept as the one path so a dynamic
     * registration cannot bypass the declaration rule.
     *
     * <p>Registering a host-assembled tool under a name the deployment already owns is refused rather
     * than allowed to shadow it: an MCP server named after a host tool must not be able to take that
     * tool's place.
     *
     * 注册一个工具，或以一个可点名的理由拒绝它。保持为唯一入口，好让任何动态注册都绕不过这条声明规则。
     *
     * <p>以部署**已经拥有**的名字注册宿主装配工具会被拒绝、而不是覆盖它：一个与宿主工具同名的 MCP 服务，
     * 不能借此顶替那个工具。
     */
    public synchronized void register(AiTool tool) {
        register(tool, Origin.DEPLOYMENT);
    }

    /** The same, naming where the tool came from. / 同上，并点明工具来自哪里。 */
    public synchronized void register(AiTool tool, Origin origin) {
        String refusal = ToolDeclaration.refusal(tool);
        if (refusal != null) {
            refused.put(tool.name(), tool);
            log.error("Refused to register AI tool `{}`: {} It is not offered to a model and will "
                            + "not execute; see /keelbase/status for the same finding. / 拒绝注册 AI "
                            + "工具 `{}`：它按写治理却没有声明 `resultType`，不会被交给模型、也不会执行；"
                            + "详见 /keelbase/status。",
                    tool.name(), refusal, tool.name());
            return;
        }
        Origin existing = origins.get(tool.name());
        if (existing == Origin.DEPLOYMENT && origin == Origin.HOST_ASSEMBLED) {
            log.error("Refused to shadow the deployment's AI tool `{}` with a host-assembled one; "
                            + "the deployment's registration stays. / 拒绝用宿主装配的工具顶替部署自己的 AI "
                            + "工具 `{}`：保留部署的那一次注册。",
                    tool.name(), tool.name());
            return;
        }
        if (existing == Origin.HOST_ASSEMBLED && origin == Origin.DEPLOYMENT) {
            log.info("Replacing host-assembled AI tool `{}` with the deployment's. / 用部署自己的 AI 工具 `{}` "
                    + "替换先前宿主装配的那一个。", tool.name(), tool.name());
        }
        tools.put(tool.name(), tool);
        origins.put(tool.name(), origin);
    }

    public synchronized AiTool require(String name) {
        AiTool tool = tools.get(name);
        if (tool != null) {
            return tool;
        }
        AiTool refusedTool = refused.get(name);
        if (refusedTool != null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "tool `" + name + "` was refused registration: "
                            + ToolDeclaration.refusal(refusedTool));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown tool: " + name);
    }

    /** Every registered tool, both origins — what the engine, policy, inventory and confirmations read. */
    /** 所有已注册工具，两个来源都算 —— 引擎、策略、清单与确认视图读的就是它。 */
    public synchronized List<AiTool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * The deployment's own catalogue only — what a deployment planner may offer to a model.
     *
     * <p>A host-assembled tool belongs to one agent's binding. Offering it here would give every agent
     * every MCP/SKILL tool, which is the reach this origin split exists to prevent.
     *
     * 只有部署自己的目录 —— 部署的规划器可以交给模型的就是这些。
     *
     * <p>宿主装配的工具属于**某一个** agent 的绑定。若在这里交出去，每个 agent 都会拿到所有 MCP/SKILL
     * 工具，而来源之分正是为了挡住这份可达面。
     */
    public synchronized List<AiTool> catalogue() {
        return tools.entrySet().stream()
                .filter(entry -> origins.get(entry.getKey()) == Origin.DEPLOYMENT)
                .map(Map.Entry::getValue)
                .toList();
    }

    /** Whether a tool is registered (of either origin). / 某个工具是否已注册（两个来源都算）。 */
    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * The governed writes registration refused, in discovery order. They are not in {@link #all()}:
     * the registry does not pretend a refused tool is available.
     *
     * 注册时被拒绝的受治理写，按发现顺序。它们不在 {@link #all()} 里：注册表不会假装一个被拒绝的
     * 工具是可用的。
     */
    public synchronized List<AiTool> refusedDeclarations() {
        return List.copyOf(refused.values());
    }
}
