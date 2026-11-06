// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A blocked call leaves its arguments nowhere but the request, so the object it meant to touch is read
 * out of them once, at the row — and read narrowly: an ambiguous map names nothing rather than the
 * wrong record.
 *
 * <p>Why this is worth its own test: the suffix is consumed mechanically by a read face, and the one
 * failure that would not be noticed is pointing at a <em>different</em> record. Each case below is a
 * shape an argument map really arrives in (a JSON number is a `Double`, a key may be `id`, `projectId`
 * or a bare count), and the two-candidate case is the one that must stay silent.
 *
 * <p>被拦下的调用把实参留在**只有请求**的地方，故「它本来要动哪条记录」在写行时从实参里读一次 —— 而且读得
 * **窄**：含糊的 map 什么都不点名，而不是点错一条。
 *
 * <p>值得单测的理由：这个后缀会被读面**机械地**读回，而唯一不会被人发现的失败，是指向**另一条**记录。
 * 下面每一例都是实参真正会到达的形状（JSON 数值是 `Double`，键可能是 `id`、`projectId` 或一个纯计数），
 * 而「两个候选」那一例必须保持沉默。
 */
class GovernedExecutionEngineBlockedObjectTest {

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    @Test
    void readsTheKeyUnderIdFirst() {
        assertEquals(" · intended object #12", GovernedExecutionEngine.intendedObject(
                args("id", 12L, "projectId", 99L, "note", "x")),
                "an explicit `id` wins over a named one");
    }

    @Test
    void readsTheSingleKeyEndingInId() {
        assertEquals(" · intended object #42", GovernedExecutionEngine.intendedObject(args("projectId", 42L)),
                "delete_project(projectId) is the shape this was written for");
    }

    @Test
    void readsAWholeJsonDouble() {
        assertEquals(" · intended object #7", GovernedExecutionEngine.intendedObject(args("customerId", 7.0d)),
                "a JSON number arrives as a Double and is still a key");
    }

    @Test
    void staysSilentWhenTwoNamedKeysTie() {
        assertEquals("", GovernedExecutionEngine.intendedObject(args("projectId", 1L, "customerId", 2L)),
                "two candidates point at no single record, so the row says nothing");
    }

    @Test
    void readsTheSingleNumericValue() {
        assertEquals(" · intended object #5", GovernedExecutionEngine.intendedObject(args("count", 5L)),
                "one number and no name for it is still one record");
    }

    @Test
    void staysSilentWhenNothingIsANumber() {
        assertEquals("", GovernedExecutionEngine.intendedObject(args("note", "x", "title", "y")));
    }

    @Test
    void refusesAFractionAndANegative() {
        assertEquals("", GovernedExecutionEngine.intendedObject(args("id", 1.5d)),
                "a fraction is not a key, and truncating it would name another record");
        assertEquals("", GovernedExecutionEngine.intendedObject(args("id", -3L)),
                "a negative number is not a key");
    }

    @Test
    void handlesNoArguments() {
        assertEquals("", GovernedExecutionEngine.intendedObject(null));
        assertEquals("", GovernedExecutionEngine.intendedObject(args()));
    }
}
