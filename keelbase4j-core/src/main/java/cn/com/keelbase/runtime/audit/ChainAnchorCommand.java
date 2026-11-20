// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The command-line door to {@link ChainAnchorService} — activated by a property, never by a route.
 *
 * <p>Three commands, all read-only except for what they print: {@code tail} (the chained tails),
 * {@code payload} (the unsigned record to be signed and published elsewhere), {@code compare} (where
 * the chain stands relative to a tail read from a file). Nothing is written to the database, and no
 * HTTP route is added: a host that sets {@code keelbase.anchor.command} gets one run at startup, and
 * the application keeps running unless {@code keelbase.anchor.exit} is set.
 *
 * <p>The exit codes are the reference CLI's: {@code 0} clean, {@code 1} a negative comparison,
 * {@code 2} usage or environment. They only become process exit codes when {@code exit=true}; a
 * server must not die because someone asked for a reading.
 *
 * <p>{@link ChainAnchorService} 的命令行入口 —— 由属性激活，**从不**经路由。
 *
 * <p>三条命令都是只读的（除了它们打印什么）：`tail`（已入链的链尾）、`payload`（供别处签名与发布的
 * 未签名记录）、`compare`（链相对一个从文件读入的链尾处于什么状态）。不写库、不新增 HTTP 路由：设了
 * `keelbase.anchor.command` 的宿主在启动时跑一次；除非设了 `keelbase.anchor.exit`，应用照常继续运行。
 *
 * <p>退出码沿用参照 CLI：`0` 干净、`1` 对照给出否定结论、`2` 用法或环境问题。只有 `exit=true` 时它们才
 * 变成进程退出码 —— 服务器不该因为有人要一次读数而死掉。
 */
@Component
@ConditionalOnProperty(prefix = "keelbase.anchor", name = "command")
public class ChainAnchorCommand implements ApplicationRunner {

    static final String COMMAND = "keelbase.anchor.command";
    static final String KNOWN = "keelbase.anchor.known";
    static final String AT = "keelbase.anchor.at";
    static final String EXIT = "keelbase.anchor.exit";

    private final ChainAnchorService anchors;
    private final String command;
    private final String known;
    private final String at;
    private final boolean exit;

    public ChainAnchorCommand(ChainAnchorService anchors,
                              @Value("${" + COMMAND + "}") String command,
                              @Value("${" + KNOWN + ":}") String known,
                              @Value("${" + AT + ":}") String at,
                              @Value("${" + EXIT + ":false}") boolean exit) {
        this.anchors = anchors;
        this.command = command;
        this.known = known;
        this.at = at;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = execute();
        if (exit) {
            System.exit(code);
        }
    }

    /** Runs one command and returns the process-worthy code; separated so a test can call it. */
    int execute() {
        try {
            return switch (command == null ? "" : command) {
                case "tail" -> tail();
                case "payload" -> payload();
                case "compare" -> compare();
                default -> usage("unknown command `" + command + "`");
            };
        } catch (IllegalArgumentException malformed) {
            return usage(malformed.getMessage());
        } catch (IOException unreadable) {
            System.err.println("cannot read `" + known + "`: " + unreadable);
            return 2;
        }
    }

    private int tail() {
        ChainAnchorService.Tails tails = anchors.tails();
        System.out.println(CanonicalJson.json(tailsJson(tails)));
        note("operationAudit is null because this runtime's operation audit is not hash-chained;"
                + " null means \"no chained row at that moment\", never \"unknown\".");
        note("operationAudit 为 null，是因为本运行时的操作审计不是哈希链；null 表示「该时刻该链没有已入链的行」，"
                + "绝不是「未知」。");
        return 0;
    }

    private int payload() {
        note("NOTE: this is an unsigned payload, not an anchor. Sign it outside this runtime and publish"
                + " it; an unsigned anchor is not evidence.");
        note("注意：这是未签名载荷，不是锚。请在本运行时之外签名并发布；未签名的锚不构成证据。");
        System.out.println(CanonicalJson.json(anchors.payload(observedAt())));
        note("operationAudit is null because this runtime's operation audit is not hash-chained.");
        note("operationAudit 为 null，因为本运行时的操作审计不是哈希链。");
        return 0;
    }

    private int compare() throws IOException {
        if (known == null || known.isBlank()) {
            return usage("compare needs -D" + KNOWN + "=<a previously produced payload or signed anchor>");
        }
        ChainAnchorService.Tails anchored =
                tailsOf(Json.parse(Files.readString(Path.of(known), StandardCharsets.UTF_8)));
        ChainAnchorService.Comparison comparison = anchors.compare(anchored);
        System.out.println(CanonicalJson.json(comparisonJson(comparison)));
        note("the comparison answers \"is the anchored tail still there\"; whether the anchor itself was"
                + " signed and published is not answered here — that is the deployer's step.");
        note("本对照回答「锚定的链尾还在不在」；锚本身有没有被签名并发布，这里不回答 —— 那是部署方的一步。");
        return comparison.ok() ? 0 : 1;
    }

    private Instant observedAt() {
        if (at == null || at.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(at);
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("-D" + AT + " is not an ISO-8601 instant: " + at);
        }
    }

    /**
     * The tails in a payload or a signed anchor. Both carry {@code chains} with <b>both</b> chain
     * keys: the schema requires them, and an absent key is not the same fact as an explicit
     * {@code null} ("no chained row at that moment"). Reading absence as {@code null} would let a
     * truncated or unrelated record compare clean, so it is refused instead.
     *
     * 载荷或已签名锚里的链尾。两者都带 `chains`，且**两个键都在**：schema 要求如此，而「键缺席」与显式
     * `null`（「该时刻该链没有已入链的行」）不是同一个事实。把缺席当成 `null`，会让被截断或不相干的记录
     * 对照出「干净」；故这里拒绝。
     */
    public static ChainAnchorService.Tails tailsOf(Object document) {
        if (!(document instanceof Map<?, ?> root) || !(root.get("chains") instanceof Map<?, ?> chains)) {
            throw new IllegalArgumentException("the known record has no `chains` object");
        }
        for (String chain : List.of("aiAudit", "operationAudit")) {
            if (!chains.containsKey(chain)) {
                throw new IllegalArgumentException(
                        "the known record's `chains` has no `" + chain + "` key — absent is not null");
            }
        }
        return new ChainAnchorService.Tails(tailOf(chains.get("aiAudit")), tailOf(chains.get("operationAudit")));
    }

    private static ChainAnchorService.Tail tailOf(Object node) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map<?, ?> tail)) {
            throw new IllegalArgumentException("a chain tail must be an object or null");
        }
        Object rows = tail.get("rows");
        Object lastId = tail.get("lastId");
        Object lastHash = tail.get("lastHash");
        if (!(rows instanceof Number r) || !(lastId instanceof Number id) || !(lastHash instanceof String hash)) {
            throw new IllegalArgumentException("a chain tail needs numeric `rows` and `lastId`, and a `lastHash`");
        }
        return new ChainAnchorService.Tail(r.longValue(), id.longValue(), hash);
    }

    private static Map<String, Object> tailsJson(ChainAnchorService.Tails tails) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aiAudit", tailJson(tails.aiAudit()));
        body.put("operationAudit", tailJson(tails.operationAudit()));
        return body;
    }

    private static Map<String, Object> tailJson(ChainAnchorService.Tail tail) {
        if (tail == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastHash", tail.lastHash());
        body.put("lastId", tail.lastId());
        body.put("rows", tail.rows());
        return body;
    }

    private static Map<String, Object> comparisonJson(ChainAnchorService.Comparison comparison) {
        Map<String, Object> chains = new LinkedHashMap<>();
        chains.put("aiAudit", chainJson(comparison.aiAudit()));
        chains.put("operationAudit", chainJson(comparison.operationAudit()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chains", chains);
        body.put("ok", comparison.ok());
        body.put("reasons", comparison.reasons());
        return body;
    }

    private static Map<String, Object> chainJson(ChainAnchorService.ChainComparison chain) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchored", tailJson(chain.anchored()));
        body.put("current", tailJson(chain.current()));
        body.put("ok", chain.ok());
        body.put("reason", chain.reason());
        body.put("status", chain.status());
        return body;
    }

    private int usage(String problem) {
        note(problem);
        note("usage: -D" + COMMAND + "=tail|payload|compare  [-D" + KNOWN + "=<path>] [-D" + AT
                + "=<ISO-8601 instant>] [-D" + EXIT + "=true]");
        return 2;
    }

    private static void note(String line) {
        System.err.println(line);
    }
}
