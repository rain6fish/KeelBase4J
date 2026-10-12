// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

/**
 * The vendored contract's schemas, and a check a wire value can be held to.
 *
 * <p>It lives in this module's test sources because that is the module whose test-jar the others
 * already reuse ({@code keelbase4j-protocol} publishes one, and {@code keelbase4j-runtime}'s tests
 * already read {@link Vectors} through it) — so a conformance check written once is a check every
 * module can apply, rather than one copy per module drifting apart.
 *
 * <p><b>The check is deliberately small and dependency-free.</b> It enforces the four things a
 * contract violation actually looks like on this wire: every {@code required} property is present; an
 * object declared {@code additionalProperties:false} carries <em>no property the schema does not
 * declare</em>; a value is drawn from the schema's {@code enum}; and a value offered a choice
 * ({@code oneOf} / {@code anyOf}) satisfies at least one branch. Anything the schema leaves open
 * stays open here too. This repository takes no JSON-schema dependency, and this is why it does not
 * need one — see {@code AppContractTest}, which says the same thing from the runtime's side.
 *
 * <p><b>What it cannot see is worth stating.</b> A value that is <em>inside</em> the vocabulary but
 * <em>wrong for the occasion</em> passes here; that class of mistake is only visible to a person
 * reading the contract's prose. The opposite is what this catches: a value that has left the frozen
 * vocabulary entirely, which is the one a machine can settle.
 *
 * 本仓 vendored 契约的 schema，以及一个可以拿来衡量 wire 取值的检查。
 *
 * <p>它住在**本模块的测试源码**里，因为别的模块**已经在复用**它的 test-jar（`keelbase4j-protocol` 发
 * test-jar，而 `keelbase4j-runtime` 的测试已经经它读 {@link Vectors}）—— 于是**写一次的符合性检查，
 * 每个模块都能用**，而不是一模块一份、各自漂移。
 *
 * <p><b>这个检查刻意很小、且零依赖。</b>它只强制契约在**这条 wire 上**真会出现的**四种**违反：`required`
 * 的属性齐全 · 声明 `additionalProperties:false` 的对象**不得多出未声明的属性** · 取值落在 schema 的
 * `enum` 内 · 给了备选（`oneOf` / `anyOf`）的取值**至少满足一个分支**。schema 留白的，这里同样留白。
 * 本仓**不引 JSON-schema 依赖**，而这就是它不需要依赖的缘故 —— 见 `AppContractTest`，它从运行时那一侧
 * 说的是同一件事。
 *
 * <p><b>它看不见什么，值得说清。</b>一个**在词表之内**、但**用错了场合**的取值，在这里是**通过**的；
 * 那一类错误只有**读契约说明文字的人**看得见。它抓的是相反的那一类：**已经越出冻结词表**的取值 ——
 * 那是机器能定的那一种。
 */
public final class WireSchemas {

    private WireSchemas() {
    }

    /**
     * The registry: contract id → schema file — v2, which settles how {@code objects[].schema} is
     * written (ADR-0007 §3 {@code Q10}). v1 is the same index carrying two spellings; a consumer that
     * has not migrated reads that one, and nothing here does.
     *
     * 登记表：契约 id → schema 文件 —— 这是 **v2**，它把 {@code objects[].schema} 的**写法**定死
     * （ADR-0007 §3 的 {@code Q10}）。v1 是同一份索引、带着**两种写法**；尚未迁移的消费方读那一份，
     * 而这里没有一处还读它。
     */
    public static Map<String, Object> registry() {
        return Vectors.map(Vectors.read("wire-schema-registry-v2.json"));
    }

    /**
     * The frozen schema for a contract id. A top-level contract is registered under {@code objects}
     * with its version; a contract the registry classifies as a <em>support schema</em> is named
     * directly and lives in the v1 tree.
     *
     * 某个 contract id 的冻结 schema。顶层契约在 {@code objects} 里带版本登记；被 registry 归类为
     * **support schema** 的契约则直接具名、住在 v1 树里。
     */
    public static Map<String, Object> schemaFor(String id) {
        for (Object o : Vectors.list(registry(), "objects")) {
            Map<String, Object> entry = Vectors.map(o);
            if (id.equals(entry.get("id"))) {
                // One way, settled in v2: `schema` is the bare file name, and the entry's own
                // `version` says which tree it sits in. This used to read two spellings, because v1
                // carried both: the bare one, and one with the version already in the path. An entry
                // that was already a path was read at `schemas/v3/v3/…`, which does not exist. That
                // branch is deleted with v1 (ADR-0007 §3 `Q10`) — not left unreachable.
                //
                // 一种写法，v2 定死的：`schema` 是**裸文件名**，它住在哪一棵树由条目自己的 `version`
                // 说。这里原本读两种写法 —— 因为 v1 两种都带：裸名，以及**路径里已经带版本**的那种。一个
                // 本身就是路径的条目会被读成 `schemas/v3/v3/…`，那是不存在的。**那段分支随 v1 一并删掉**
                // （ADR-0007 §3 的 `Q10`）—— 不是留着不用。
                String named = String.valueOf(entry.get("schema"));
                String version = String.valueOf(entry.get("version"));
                return Vectors.map(Vectors.read("schemas/" + version + "/" + named));
            }
        }
        for (Object s : Vectors.list(registry(), "supportSchemas")) {
            if (s.equals(id + ".schema.json")) {
                return Vectors.map(Vectors.read("schemas/v1/" + s));
            }
        }
        throw new IllegalStateException("contract is not in the vendored registry: " + id);
    }

    /**
     * Validate a wire value against a frozen schema, recursively — see the class javadoc for what it
     * does and does not enforce.
     *
     * 拿一份冻结 schema **递归**校验一个 wire 取值 —— 它强制什么、不强制什么，见类注释。
     */
    public static void assertConforms(Object value, Map<String, Object> schema) {
        assertConforms(value, schema, schema);
    }

    /**
     * The same check, carrying the document a local reference has to be resolved against.
     *
     * <p>{@code $ref} comes in two shapes and the check has to tell them apart: one names another file
     * (read from the v1 tree, where the shared support schemas live), the other points inside the
     * document being read ({@code #/definitions/item}, as the v3 side-effect schema does). Reading the
     * second as the first asks for a file called {@code v3/v3/…} and fails on it.
     *
     * 同一份检查，另外带着「局部引用要对着哪份文档解析」。
     *
     * <p>{@code $ref} 有两种形状，检查必须分得开：一种指名**另一个文件**（从 v1 树里读，共享的 support
     * schema 住在那儿），另一种指向**正在读的这份文档内部**（如 v3 的 side-effect schema 用的
     * `#/definitions/item`）。把后一种当前一种读，会去找一个叫 `v3/v3/…` 的文件、并栽在上面。
     */
    private static void assertConforms(Object value, Map<String, Object> schema,
                                       Map<String, Object> document) {
        if (schema.containsKey("$ref")) {
            Object ref = schema.get("$ref");
            String pointer = String.valueOf(ref);
            assertConforms(value,
                    pointer.startsWith("#/")
                            ? resolveWithin(document, pointer)
                            : Vectors.map(Vectors.read("schemas/v1/" + ref)),
                    document);
            return;
        }
        // A schema that offers alternatives has to be tried, not skimmed. Without this the check
        // found no `required` and no `properties`, and therefore passed everything that reached it —
        // a green that says nothing, which is the one outcome a conformance check must not have.
        // `oneOf` and `anyOf` are read the same way here: the value has to satisfy at least one
        // branch, which is all the difference between them that this check can act on.
        //
        // 一份给出几个**备选**的 schema，必须**逐个试过**，不能略过。没有这一段，检查在那儿既找不到
        // `required` 也找不到 `properties`，于是**凡是走到那里的取值一律通过** —— 一个什么都不说的绿，
        // 而那是符合性检查最不能有的结果。这里把 `oneOf` 与 `anyOf` 读成同一种：取值至少要满足**其中一个**
        // 分支 —— 那也正是本检查能对两者差别做的全部。
        for (String alternatives : List.of("oneOf", "anyOf")) {
            if (schema.get(alternatives) instanceof List<?> branches && !branches.isEmpty()) {
                boolean satisfied = false;
                for (Object branch : branches) {
                    if (tryConforms(value, Vectors.map(branch), document)) {
                        satisfied = true;
                        break;
                    }
                }
                assertTrue(satisfied,
                        "no `" + alternatives + "` branch accepts the value: " + value);
                return;
            }
        }
        if (schema.get("enum") instanceof List<?> allowed && value != null) {
            assertTrue(allowed.contains(value), "value outside the frozen enum: " + value);
            return;
        }
        if (value instanceof Map<?, ?> wire) {
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> p
                    ? Vectors.map(p) : Map.of();
            if (schema.get("required") instanceof List<?> required) {
                for (Object r : required) {
                    assertTrue(wire.containsKey(r), "required property missing: " + r);
                }
            }
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                // "No property beyond those declared" — which is what the keyword means, and what
                // {@code required} complements: a schema with {@code additionalProperties:false} and an
                // optional property is satisfied by a wire that omits it. This was written as set
                // equality when the check lived in one test, where every carrier happened to carry
                // every property; the first endpoint to be held to it — a failure body that declares
                // six optional fields and sends two — showed the difference.
                //
                // **「不得多出**已声明的属性」—— 这才是这个关键字的意思，也是 {@code required} 与之互补的
                // 地方：一份 `additionalProperties:false` 且带可选属性的 schema，**允许** wire 不带那个属性。
                // 这条检查先前（住在一份测试里时）写成了**集合相等**，而那里的每个 carrier 恰好都带全了属性；
                // 第一个被它衡量的端到端形状 —— 一份声明六个可选字段、只发两个的失败体 —— 把差别摆了出来。
                for (Object key : wire.keySet()) {
                    assertTrue(properties.containsKey(key),
                            "additionalProperties:false — the wire carries `" + key
                                    + "`, which the schema does not declare");
                }
            }
            for (Object key : wire.keySet()) {
                if (properties.get(key) instanceof Map<?, ?> sub) {
                    assertConforms(wire.get(key), Vectors.map(sub), document);
                }
            }
        } else if (value instanceof List<?> items && schema.get("items") instanceof Map<?, ?> item) {
            for (Object v : items) {
                assertConforms(v, Vectors.map(item), document);
            }
        }
    }

    /** A wire value against a named contract, in one call. */
    public static void assertConformsTo(Object value, String contractId) {
        assertConforms(value, schemaFor(contractId));
    }

    /**
     * Follow a {@code #/…} pointer inside the document being read — {@code #/definitions/item} and its
     * kind, which the versioned schemas use to name their own branches.
     *
     * 在**正在读的这份文档内部**跟一个 {@code #/…} 指针 —— 也就是带版本的 schema 用来指名自己分支的
     * `#/definitions/item` 那一类。
     */
    private static Map<String, Object> resolveWithin(Map<String, Object> document, String pointer) {
        Object node = document;
        for (String segment : pointer.substring(2).split("/")) {
            node = node instanceof Map<?, ?> map
                    ? map.get(segment.replace("~1", "/").replace("~0", "~")) : null;
            if (node == null) {
                throw new IllegalStateException("the schema points at something it does not define: "
                        + pointer);
            }
        }
        return Vectors.map(node);
    }

    /**
     * Whether a value satisfies a schema — used only where a schema offers alternatives and the check
     * has to find out which, if any, the value belongs to. The branch's own message is deliberately
     * dropped: what a reader needs at that point is the one the caller prints when none of them fit.
     *
     * 一个取值**是否**满足某份 schema —— 只在 schema 给出备选、检查必须弄清它属于哪一个（或哪个都不属于）
     * 时使用。分支自己的消息**刻意丢掉**：那个位置上读者需要的，是「没有一个分支合适」时调用方打出来的那句。
     */
    private static boolean tryConforms(Object value, Map<String, Object> schema,
                                       Map<String, Object> document) {
        try {
            assertConforms(value, schema, document);
            return true;
        } catch (AssertionError notThisBranch) {
            return false;
        }
    }
}
