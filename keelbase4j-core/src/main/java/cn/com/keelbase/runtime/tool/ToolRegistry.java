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

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final Map<String, AiTool> refused = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> beans) {
        for (AiTool tool : beans) {
            register(tool);
        }
    }

    /**
     * Register one tool, or refuse it with a nameable reason. Kept as the one path so a future
     * dynamic registration cannot bypass the declaration rule.
     *
     * 注册一个工具，或以一个可点名的理由拒绝它。保持为唯一入口，好让将来任何动态注册都绕不过这条
     * 声明规则。
     */
    private void register(AiTool tool) {
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
        tools.put(tool.name(), tool);
    }

    public AiTool require(String name) {
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

    public List<AiTool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * The governed writes registration refused, in discovery order. They are not in {@link #all()}:
     * the registry does not pretend a refused tool is available.
     *
     * 注册时被拒绝的受治理写，按发现顺序。它们不在 {@link #all()} 里：注册表不会假装一个被拒绝的
     * 工具是可用的。
     */
    public List<AiTool> refusedDeclarations() {
        return List.copyOf(refused.values());
    }
}
