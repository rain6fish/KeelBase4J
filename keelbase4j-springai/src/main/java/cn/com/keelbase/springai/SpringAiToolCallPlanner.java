// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The model-driven planner: Spring AI's {@link ChatClient} on one side, the runtime's seam on the
 * other.
 *
 * <p>It is an adapter in the strict sense — it holds no governance state and makes no governance
 * decision. What it returns is an {@link IntentPlan}, which carries a tool name and arguments and
 * nothing else; risk level, the confirmation requirement, the audit entry and the revoke path are
 * read from the tool's own declaration downstream and applied unconditionally. A model that wanted
 * to talk its way past the gate would have nothing to say it with.
 *
 * <p>Two things are deliberately withheld from the model:
 *
 * <ul>
 *   <li><b>Governance metadata.</b> The catalogue it sees is name and description only. {@link
 *       AiTool} carries more ({@code riskLevel}, {@code requiresConfirmation}, {@code revokeClass})
 *       and marks it "server-side only, never sent to a model" — this is where that is honoured
 *       rather than merely stated.
 *   <li><b>The tool's own execution.</b> The reply is read as a proposal and handed back. Nothing
 *       is invoked here, so the only path from a model's choice to a side effect runs through the
 *       engine, under the gate, exactly as a hand-written plan would.
 * </ul>
 */
public final class SpringAiToolCallPlanner implements ToolCallPlanner {

    private static final String INSTRUCTIONS = """
            You route one request to at most one tool.
            Reply with a single JSON object and nothing else — no prose, no code fence:
              {"tool": "<name>", "args": {...}}
            or, when no listed tool fits the request:
              {"tool": null}
            Use only the tool names listed below. Never invent one.
            """;

    private final ChatClient chatClient;
    private final ToolRegistry tools;

    public SpringAiToolCallPlanner(ChatClient chatClient, ToolRegistry tools) {
        this.chatClient = chatClient;
        this.tools = tools;
    }

    @Override
    public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
        String reply = chatClient.prompt()
                .system(system())
                .user(turn(message, context))
                .call()
                .content();
        return read(reply);
    }

    /**
     * The catalogue the model is allowed to see: names and descriptions, and deliberately nothing
     * about what happens to a call once it has been proposed.
     */
    private String system() {
        StringBuilder sb = new StringBuilder(INSTRUCTIONS);
        sb.append("\nAvailable tools:\n");
        for (AiTool tool : tools.all()) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString();
    }

    private String turn(String message, Map<String, Object> context) {
        StringJoiner turn = new StringJoiner("\n");
        turn.add("request: " + (message == null ? "" : message));
        if (context != null && !context.isEmpty()) {
            turn.add("context: " + context);
        }
        return turn.toString();
    }

    /**
     * A reply becomes a plan only if it names a tool the runtime actually has. An unparseable reply,
     * a missing name, or a name that is not in the registry all mean the same thing — nothing fits —
     * and the caller sees that rather than a speculative call.
     */
    private Optional<IntentPlan> read(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        Object parsed;
        try {
            parsed = Json.parse(unfence(reply));
        } catch (RuntimeException notJson) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map<?, ?> object)) {
            return Optional.empty();
        }
        if (!(object.get("tool") instanceof String name) || name.isBlank() || !known(name)) {
            return Optional.empty();
        }
        return Optional.of(new IntentPlan(name, argsOf(object.get("args"))));
    }

    private boolean known(String name) {
        return tools.all().stream().anyMatch(tool -> tool.name().equals(name));
    }

    /** Arguments are passed through as data; a proposal with none is a proposal with none. */
    private Map<String, Object> argsOf(Object args) {
        if (!(args instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /** Models like to wrap JSON in a code fence; the fence is not part of the answer. */
    private String unfence(String reply) {
        String text = reply.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence).trim();
    }
}
