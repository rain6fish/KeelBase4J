// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The optional {@code releaseLookups} column, as the contract's vector pins it (F-85b).
 *
 * <p>This runtime does not take target-status lookups yet, so it never fills the column — but it does
 * <em>consume</em> audit rows, and a consumer must accept the shape the contract allows and refuse the
 * ones it does not. The vector carries both: a row with a JSON string, one without the column, one with
 * an explicit null, one whose column is an object, and one with an undeclared key.
 *
 * <p>本运行时还没有目标状态查询，故它从不填这一列 —— 但它**消费**审计行，而消费方必须接受契约允许的形状、
 * 拒绝不允许的。向量把两边都给了：带 JSON 文本的、不带这一列的、显式 null 的、值是对象的、以及多一个
 * 未声明键的。
 */
class ReleaseEvidenceVectorTest {

    private static final String VECTOR = "ai-audit-log-release-evidence-v1-vector.json";

    @Test
    void everyCaseAgreesWithTheV8Shape() {
        Map<String, Object> vector = Vectors.map(Vectors.read(VECTOR));
        Map<String, Object> schema = WireSchemas.schemaFor("ai-audit-log-row");

        List<Object> cases = Vectors.list(vector, "cases");
        assertTrue(cases.size() >= 5, "the vector pins at least the five shapes it names");

        for (Object raw : cases) {
            Map<String, Object> testCase = Vectors.map(raw);
            Map<String, Object> row = Vectors.map(testCase.get("in"));
            boolean expected = Boolean.TRUE.equals(Vectors.map(testCase.get("expect")).get("legal"));
            String name = (String) testCase.get("name");

            if (expected) {
                WireSchemas.assertConforms(row, schema);
            } else {
                assertThrows(AssertionError.class,
                        () -> WireSchemas.assertConforms(row, schema), name);
            }
        }
    }

    /**
     * The legal row is not just shape-legal — its text parses, and what it carries is readable. A
     * consumer that can only check "is a string" would pass the first test and still be unable to
     * answer "did both probes say not there".
     *
     * 合法的那一行不只是形状合法 —— 它的文本能解析，且里面的东西读得出来。只能判「是不是字符串」的消费方
     * 能过第一条测试，却仍然答不出「两次探测是不是都说没执行」。
     */
    @Test
    void theEvidenceItselfIsReadable() {
        Map<String, Object> vector = Vectors.map(Vectors.read(VECTOR));
        Map<String, Object> first = Vectors.map(Vectors.list(vector, "cases").get(0));

        String text = (String) Vectors.map(first.get("in")).get("releaseLookups");
        assertNotNull(text, "the first case carries the evidence");

        Map<String, Object> evidence = Vectors.map(Json.parse(text));
        assertEquals(2, ((Number) evidence.get("count")).intValue());
        List<Object> items = Vectors.list(evidence, "items");
        assertEquals(2, items.size());
        for (Object item : items) {
            Map<String, Object> reading = Vectors.map(item);
            assertNotNull(reading.get("at"), "every reading says when it was taken");
            assertNotNull(reading.get("lookedUpBy"), "every reading says who asked");
            assertTrue(reading.containsKey("conclusion"), "every reading says what the target answered");
        }
    }
}
