// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The governance policy in force: what it says, and what saying it does.
 *
 * <p><b>Nothing is accepted and then ignored.</b> The frozen object leaves room for keys this runtime
 * does not read, and the tempting answer is to store them and move on — which would put a setting on a
 * console that nothing enforces, the failure a reader cannot tell from a working one. So a policy naming
 * a tool this deployment does not register, a mode outside the frozen three, or a role outside this
 * deployment's vocabulary is <em>refused</em> with the reason, in the same spirit as the audit endpoint
 * refusing a filter it cannot apply. The two derived keys are the exception and are not an inconsistency:
 * {@code updatedAt} and {@code revision} are this object's own fields, filled in here, so a console that
 * reads a policy and writes it back is not refused for returning what it was handed.
 *
 * <p><b>The revision is content, not a counter.</b> It is the digest of the canonical JSON cut to the
 * width the frozen object documents (its first twelve hex), so submitting the same policy twice produces
 * the same revision rather than a version of nothing — and a policy read and written back unchanged does
 * not grow the history.
 *
 * <p>**生效中的治理策略**：它说什么，以及说出口之后做什么。
 *
 * <p><b>没有任何东西是「接受了、然后被忽略」的。</b> 冻结对象为**本运行时不读的键**留了空间，而顺手的做法是**存下来、
 * 接着往下走** —— 那等于把一项**没有任何东西施加**的设置显示到控制台上，而读的人**分不出**它和一项在生效的设置。所以：
 * 点名了本部署**没有注册**的工具、用了冻结三值之外的 `mode`、或点名了本部署**词表之外**的角色，都会被**拒绝并给出理由**
 * —— 与审计端点**拒绝一个它施加不了的筛选**同一个道理。两个**派生键**是例外、也不构成不一致：`updatedAt` 与 `revision`
 * 是这个对象**自己的字段**、在这里填上，所以一个控制台**读出来再写回去**，不会因为「把它拿到的东西还回来」被拒。
 *
 * <p><b>revision 是内容、不是计数。</b> 它是规范化 JSON 摘要**截到冻结对象写明的宽度**（前十二位十六进制），所以
 * **同一份策略提交两次得到同一个 revision**，而不是凭空多出一个版本 —— 一份**读出来原样写回**的策略也不会让历史变长。
 */
@Component
public class GovernancePolicyStore {

    /** The frozen vocabulary's own granularity, and what is answered when nothing is stored. */
    public static final String DEFAULT_GRANULARITY = "all";

    private static final Set<String> GRANULARITIES = Set.of("all", "write", "off");
    private static final Set<String> MODES = Set.of("auto", "confirm", "approval");
    /** This deployment's role vocabulary — the contract's own two values, and nothing else. */
    private static final Set<String> KNOWN_ROLES = Set.of(Principal.ROLE_USER, Principal.ROLE_ADMIN);
    private static final Set<String> OVERRIDE_KEYS =
            Set.of("enabled", "requiresConfirmation", "allowedRoles", "mode");
    private static final Set<String> AUDIT_KEYS = Set.of("granularity");
    private static final Set<String> STORED_KEYS = Set.of("tools", "audit", "updatedAt", "revision");
    /** The revision's width in hex digits, as the frozen object documents it. */
    private static final int REVISION_HEX = 12;

    private final GovernancePolicyRepository policies;
    private final GovernancePolicyHistoryRepository history;
    private final ToolRegistry tools;

    public GovernancePolicyStore(GovernancePolicyRepository policies,
                                 GovernancePolicyHistoryRepository history, ToolRegistry tools) {
        this.policies = policies;
        this.history = history;
        this.tools = tools;
    }

    /** The policy in force, or the frozen default when none was ever written. */
    public Map<String, Object> current() {
        return policies.findAll().stream().findFirst()
                .map(row -> view(row.getPolicyJson(), row.getUpdatedAt()))
                .orElseGet(() -> view(defaultJson(), null));
    }

    /**
     * Replace the policy — or refuse the submission, in whole, with the reason. A policy is not applied
     * field by field: half of one is a gate nobody asked for.
     */
    public Map<String, Object> set(Map<String, Object> body) {
        rejectUnknown(body.keySet(), STORED_KEYS, "the policy");
        Map<String, Object> tools = requireMap(body.get("tools"), "`tools`");
        Map<String, Object> audit = requireMap(body.get("audit"), "`audit`");
        for (Map.Entry<String, Object> entry : tools.entrySet()) {
            if (this.tools.all().stream().noneMatch(tool -> tool.name().equals(entry.getKey()))) {
                throw refused("`tools` names `" + entry.getKey() + "`, which this deployment does not "
                        + "register — an override for it could only be ignored");
            }
            validateOverride(entry.getKey(), requireMap(entry.getValue(),
                    "the override for `" + entry.getKey() + "`"));
        }
        rejectUnknown(audit.keySet(), AUDIT_KEYS, "`audit`");
        if (!GRANULARITIES.contains(String.valueOf(audit.get("granularity")))) {
            throw refused("`audit.granularity` must be one of " + GRANULARITIES + ", not `"
                    + audit.get("granularity") + "`");
        }
        String json = CanonicalJson.json(ordered(tools, audit));
        GovernancePolicyRow row = policies.findAll().stream().findFirst()
                .orElseGet(() -> new GovernancePolicyRow(json));
        row.setPolicyJson(json);
        policies.save(row);
        String revision = revision(json);
        // The revision is content, so an unchanged policy must not append another snapshot.
        boolean alreadyRecorded = history.findFirstByOrderByIdDesc()
                .map(snapshot -> revision.equals(snapshot.getRevision()))
                .orElse(false);
        if (!alreadyRecorded) {
            history.save(new GovernancePolicyHistoryRow(revision, json));
        }
        return view(json, row.getUpdatedAt());
    }

    /**
     * What the policy says about one tool, or an empty map — the shape every reader here wants, so that
     * "no override" and "an override that says nothing" cannot be told apart by accident.
     */
    public Map<String, Object> overrideFor(String toolName) {
        Object stored = currentTools().get(toolName);
        return stored instanceof Map<?, ?> override ? stringKeys(override) : Map.of();
    }

    /**
     * How much this deployment writes to its AI audit chain: {@code all}, {@code write} or {@code off}.
     *
     * <p>This is the frozen object's own dimension, and it governs the AI audit rather than the
     * operations chain: {@code off} stops the rows entirely, {@code write} keeps the tool calls. This
     * runtime writes only tool-scoped rows — it records no conversation-scoped ones — so here {@code
     * all} and {@code write} come to the same set, and {@code off} is the one that changes anything.
     *
     * 本部署往它的 **AI 审计链**里写多少：`all` / `write` / `off`。
     *
     * <p>这是冻结对象自带的维度，管的是 **AI 审计**、不是操作链：`off` 完全不写行，`write` 留下工具调用。
     * 本运行时只写**工具范围**的行、不写会话范围的行，故在这里 `all` 与 `write` 落到同一集合，真正改变的只有 `off`。
     */
    public String granularity() {
        Object audit = current().get("audit");
        Object granularity = audit instanceof Map<?, ?> block ? block.get("granularity") : null;
        return granularity == null ? DEFAULT_GRANULARITY : String.valueOf(granularity);
    }

    /** The revision of the policy in force — the fingerprint a decision is recorded under. */
    public String currentRevision() {
        return String.valueOf(current().get("revision"));
    }

    private Map<String, Object> currentTools() {
        Object stored = current().get("tools");
        return stored instanceof Map<?, ?> all ? stringKeys(all) : Map.of();
    }

    private static void validateOverride(String toolName, Map<String, Object> override) {
        rejectUnknown(override.keySet(), OVERRIDE_KEYS, "the override for `" + toolName + "`");
        for (String flag : List.of("enabled", "requiresConfirmation")) {
            if (override.containsKey(flag) && !(override.get(flag) instanceof Boolean)) {
                throw refused("`" + toolName + "." + flag + "` must be true or false");
            }
        }
        if (override.containsKey("mode") && !MODES.contains(String.valueOf(override.get("mode")))) {
            throw refused("`" + toolName + ".mode` must be one of " + MODES + " — a policy can tighten "
                    + "or relax a gate, but it cannot invent a mode");
        }
        if (override.containsKey("allowedRoles")) {
            if (!(override.get("allowedRoles") instanceof List<?> roles)) {
                throw refused("`" + toolName + ".allowedRoles` must be a list of roles");
            }
            for (Object role : roles) {
                if (!KNOWN_ROLES.contains(String.valueOf(role))) {
                    // Refused rather than stored: a console would render the list as a restriction,
                    // while nothing here would ever enforce it.
                    throw refused("`" + toolName + ".allowedRoles` names `" + role + "`, and this "
                            + "deployment's roles are " + KNOWN_ROLES + " — a list it cannot apply is "
                            + "refused rather than stored");
                }
            }
        }
    }

    private static void rejectUnknown(Set<String> keys, Set<String> allowed, String where) {
        List<String> unknown = keys.stream().filter(key -> !allowed.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw refused(where + " names " + unknown + ", which this deployment does not apply — "
                    + "accepted and ignored is the one answer it does not give");
        }
    }

    private static Map<String, Object> requireMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw refused(what + " must be an object");
        }
        return stringKeys(map);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static Map<String, Object> view(String json, java.time.Instant updatedAt) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.putAll(Json.parse(json) instanceof Map<?, ?> stored ? stringKeys(stored) : Map.of());
        policy.put("updatedAt", updatedAt);
        policy.put("revision", revision(json));
        return policy;
    }

    private static String defaultJson() {
        return CanonicalJson.json(ordered(Map.of(), Map.of("granularity", DEFAULT_GRANULARITY)));
    }

    /**
     * The policy as the bytes a revision is taken over: every key ordered, so the same content always
     * serialises the same way and "same content, same revision" holds across processes — not merely
     * within one.
     *
     * <p>It is not cosmetic. {@code CanonicalJson.json} emits keys in <em>insertion</em> order, and
     * {@code Map.of}'s iteration order is unspecified while a submitted override's key order is the
     * caller's business — so hashing whatever a submission happened to spell would give one policy two
     * revisions, which is precisely what a content fingerprint promises not to do. The reference
     * normalises the same way (its {@code normalizePolicy} sorts tool names, sorts {@code allowedRoles}
     * and writes the keys in a fixed order) before it stringifies.
     *
     * 策略按其**取 revision 所依据的字节**成形：每个键有序，故同一内容总是序列化成同一串、且「同内容同号」
     * **跨进程**成立 —— 不只是在同一个进程里成立。
     *
     * <p>不是装饰。`CanonicalJson.json` 按**插入序**发键，而 `Map.of` 的迭代序未定义、一次提交里的覆盖键序又是
     * 调用方的事 —— 于是对「这次提交碰巧怎么写的」取哈希，会让同一份策略得到两个 revision，而那正是一枚**内容
     * 指纹**承诺不会发生的事。参照实现同样先归一（它的 `normalizePolicy` 排工具名、排 `allowedRoles`、按固定
     * 顺序写键）再串化。
     */
    private static Map<String, Object> ordered(Map<String, Object> tools, Map<String, Object> audit) {
        Map<String, Object> sortedTools = new LinkedHashMap<>();
        tools.keySet().stream().sorted().forEach(name -> {
            Map<String, Object> override = requireMap(tools.get(name), "the override for `" + name + "`");
            Map<String, Object> orderedOverride = new LinkedHashMap<>();
            for (String key : List.of("enabled", "requiresConfirmation", "mode", "allowedRoles")) {
                if (override.containsKey(key)) {
                    orderedOverride.put(key, "allowedRoles".equals(key)
                            ? sortedRoles(override.get(key))
                            : override.get(key));
                }
            }
            sortedTools.put(name, orderedOverride);
        });
        Map<String, Object> orderedAudit = new LinkedHashMap<>();
        orderedAudit.put("granularity", audit.get("granularity"));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("tools", sortedTools);
        policy.put("audit", orderedAudit);
        return policy;
    }

    /** A role list in a fixed order, so two submissions of the same set fingerprint the same. */
    private static List<String> sortedRoles(Object roles) {
        List<String> sorted = new ArrayList<>();
        ((List<?>) roles).forEach(role -> sorted.add(String.valueOf(role)));
        sorted.sort(null);
        return sorted;
    }

    private static String revision(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            // The frozen object documents the revision as the first twelve hex of that digest; the
            // column is wider so a deployment that one day wants the whole thing is not migrating.
            //
            // 冻结对象把 revision 写成那份摘要的**前十二位十六进制**；列留得更宽，是为了将来真要用整份
            // 摘要的部署不必再迁一次。
            return hex.substring(0, REVISION_HEX);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static ResponseStatusException refused(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
    }
}
