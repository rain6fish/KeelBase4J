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
 * <p><b>The check is deliberately small and dependency-free.</b> It enforces the three things a
 * contract violation actually looks like on this wire: every {@code required} property is present; an
 * object declared {@code additionalProperties:false} carries <em>no property the schema does not
 * declare</em>; and a value is drawn from the schema's {@code enum}. Anything the schema leaves open
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
 * <p><b>这个检查刻意很小、且零依赖。</b>它只强制契约在**这条 wire 上**真会出现的三种违反：`required` 的
 * 属性齐全 · 声明 `additionalProperties:false` 的对象**恰好**带 schema 的那些属性 · 取值落在 schema 的
 * `enum` 内。schema 留白的，这里同样留白。本仓**不引 JSON-schema 依赖**，而这就是它不需要依赖的缘故 ——
 * 见 `AppContractTest`，它从运行时那一侧说的是同一件事。
 *
 * <p><b>它看不见什么，值得说清。</b>一个**在词表之内**、但**用错了场合**的取值，在这里是**通过**的；
 * 那一类错误只有**读契约说明文字的人**看得见。它抓的是相反的那一类：**已经越出冻结词表**的取值 ——
 * 那是机器能定的那一种。
 */
public final class WireSchemas {

    private WireSchemas() {
    }

    /** The registry: contract id → schema file. */
    public static Map<String, Object> registry() {
        return Vectors.map(Vectors.read("wire-schema-registry.json"));
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
                return Vectors.map(Vectors.read(
                        "schemas/" + entry.get("version") + "/" + entry.get("schema")));
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
        if (schema.containsKey("$ref")) {
            assertConforms(value, Vectors.map(Vectors.read("schemas/v1/" + schema.get("$ref"))));
            return;
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
                    assertConforms(wire.get(key), Vectors.map(sub));
                }
            }
        } else if (value instanceof List<?> items && schema.get("items") instanceof Map<?, ?> item) {
            for (Object v : items) {
                assertConforms(v, Vectors.map(item));
            }
        }
    }

    /** A wire value against a named contract, in one call. */
    public static void assertConformsTo(Object value, String contractId) {
        assertConforms(value, schemaFor(contractId));
    }
}
