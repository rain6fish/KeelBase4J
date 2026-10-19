// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import cn.com.keelbase.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The coverage-gap registry, read from the vendored mirror and evaluated against a fact set.
 *
 * <p>The registry has one source, in the TypeScript repository's `coverage-gaps.ts`; the file read
 * here is its mirror (`conformance/coverage-gaps.json`), kept in step by a cross-repository gate.
 * This class decides <b>nothing</b> about which gaps exist: adding an entry to the mirror makes it
 * appear in every statement with no change here, which is the property the statement exists for. A
 * predicate naming a fact outside the closed set is refused at load time rather than evaluated as
 * "never applies" — a typo must not quietly drop a caveat.
 *
 * <p>覆盖缺口登记表，从 vendored 镜像读入、并在一组事实上求值。
 *
 * <p>登记表只有**一个**源，在 TypeScript 仓的 `coverage-gaps.ts`；这里读的是它的镜像
 * （`conformance/coverage-gaps.json`），由一道跨仓闸保持同步。本类**不决定**有哪些缺口 ——
 * 往镜像里加一条，它就会出现在每份声明里、本类一字不改，而那正是这份声明存在的性质。谓词点名了闭集之外的
 * 事实时**在装载期拒绝**，而不是求值成「永不适用」—— 一个拼写错误不能悄悄丢掉一条告诫。
 */
@Component
public class CoverageGaps {

    /** The mirror's classpath path. / 镜像在 classpath 上的路径。 */
    static final String RESOURCE = "/coverage-gaps.json";

    /**
     * The facts a predicate may read — closed on purpose. A predicate that can read anything is a
     * predicate nobody can evaluate.
     *
     * 谓词可读的事实 —— 有意闭集。什么都能读的谓词等于没人能求值的谓词。
     */
    static final Set<String> KNOWN_FACTS = Set.of(
            "runtime",
            "host",
            "hostOwnsBusinessWrite",
            "hostOwnAiPath",
            "auditGranularity",
            "sharedCacheAcrossDeployments",
            "nonDiscreteActionSurfacesExposed",
            "packageHasExternalAnchor",
            "packageHasEffectWithoutSnapshot",
            "packageHasOpenDispute",
            "packageHasStalledRevoke",
            "packageHasChatRow");

    private static final Comparator<CoverageGap> DISPLAY_ORDER =
            Comparator.comparingInt(CoverageGap::severityRank).thenComparing(CoverageGap::id);

    private final List<CoverageGap> gaps;
    private final String registryRevision;

    public CoverageGaps() {
        this(readResource());
    }

    private CoverageGaps(String json) {
        this.gaps = List.copyOf(parse(json));
        this.registryRevision = revisionOf(json);
    }

    /** Test seam: build a registry from a JSON document without touching the classpath. / 测试缝：不经 classpath 构造登记表。 */
    static CoverageGaps fromJson(String json) {
        return new CoverageGaps(json);
    }

    /** The gaps that apply to {@code facts}, in the order every statement shows them. / 适用于该组事实的缺口，按声明展示顺序。 */
    public List<CoverageGap> applicable(Map<String, Object> facts) {
        return gaps.stream().filter(gap -> gap.applies(facts)).sorted(DISPLAY_ORDER).toList();
    }

    /** Identifies which registry a statement was computed against. / 标明一份声明按哪一版登记表算出。 */
    public String registryRevision() {
        return registryRevision;
    }

    /**
     * The verdict is <b>derived</b>, never written: no applicable gap ⇒ {@code complete}; any
     * applicable gap that cannot even say what is missing ⇒ {@code unknown}; otherwise
     * {@code partial}. `unknown` is not a stronger `partial` — it is the admission that the missing
     * part is not nameable.
     *
     * 结论是**算出来**的、从不手写：无适用缺口 ⇒ `complete`；任一适用缺口连「漏了什么」都说不出 ⇒
     * `unknown`；否则 `partial`。`unknown` **不是**更强的 `partial` —— 它是「漏的那部分叫不出名字」的承认。
     */
    public static String deriveVerdict(List<CoverageGap> applicable) {
        if (applicable.isEmpty()) {
            return "complete";
        }
        for (CoverageGap gap : applicable) {
            if ("unknown".equals(gap.verdictImpact())) {
                return "unknown";
            }
        }
        return "partial";
    }

    private static String readResource() {
        try (InputStream in = CoverageGaps.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "the vendored coverage-gap mirror is missing from the classpath at " + RESOURCE
                                + "; the core's build must copy conformance/coverage-gaps.json into it");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    private static List<CoverageGap> parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> document) || !(document.get("gaps") instanceof List<?> raw)) {
            throw new IllegalStateException("coverage-gaps.json must be an object with a `gaps` array");
        }
        List<CoverageGap> parsed = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> gap)) {
                throw new IllegalStateException("every coverage gap must be an object");
            }
            parsed.add(new CoverageGap(
                    required(gap, "id"),
                    required(gap, "severity"),
                    required(gap, "affects"),
                    required(gap, "verdictImpact"),
                    conditions(gap),
                    required(gap, "statement"),
                    required(gap, "origin")));
        }
        return parsed;
    }

    private static List<CoverageGap.Condition> conditions(Map<?, ?> gap) {
        if (!(gap.get("appliesWhen") instanceof List<?> entries)) {
            throw new IllegalStateException(
                    "gap `" + gap.get("id") + "` must carry an `appliesWhen` array (empty means always)");
        }
        List<CoverageGap.Condition> conditions = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> condition)) {
                throw new IllegalStateException("a condition must be an object");
            }
            String fact = required(condition, "fact");
            if (!KNOWN_FACTS.contains(fact)) {
                throw new IllegalStateException(
                        "gap `" + gap.get("id") + "` reads unknown fact `" + fact + "`; the closed set is "
                                + KNOWN_FACTS);
            }
            Object expected = condition.get("equals");
            if (!(expected instanceof Boolean) && !(expected instanceof String)) {
                throw new IllegalStateException(
                        "condition `" + fact + "` in gap `" + gap.get("id") + "` must compare to a"
                                + " boolean or a string");
            }
            conditions.add(new CoverageGap.Condition(fact, expected));
        }
        return conditions;
    }

    private static String required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("coverage gap entry is missing `" + key + "`");
        }
        return text;
    }

    /**
     * First twelve hex of the digest of the mirror's text, line endings normalised. It identifies
     * the registry a statement was computed against; it is not claimed to equal the TypeScript
     * registry's revision, whose digest is taken over a code object rather than this file.
     *
     * 镜像文本摘要的前十二位十六进制，行尾先规范化。它标明一份声明按**哪一版**登记表算出；
     * **不**声称与 TypeScript 登记表的 revision 相等 —— 后者是对一个代码对象取摘要。
     */
    private static String revisionOf(String json) {
        String normalised = json.replace("\r\n", "\n").trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JRE", impossible);
        }
    }
}
