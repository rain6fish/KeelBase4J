// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.runtime.effect.ResultIds;
import org.junit.jupiter.api.Test;

/**
 * The ledger stores a result id as text; the wire keeps it the shape it had.
 *
 * <p>This exists because two rules meet here and only one of them may change: the storage column had to
 * widen to text so a UUID-keyed object could be recorded at all, and the payloads delivered before that
 * change carry integer ids that the frozen object types as numbers. Turning those into {@code "42"}
 * would be a wire change made by a storage decision, so the rule is pinned: canonical decimal text is
 * written as a number, everything else as the text it is, and nothing is rewritten on the way through.
 *
 * 账本把结果 id 存成文本；wire 上保持它本来的形状。
 *
 * <p>之所以有这一条，是因为这里会合了两条规则、而只允许其中一条改变：存储列必须放宽到文本，否则以 UUID 为键的
 * 对象根本无法记下；而在那次改变之前发出的载荷带着整数 id，冻结对象把它们定为数字。把它们变成 `"42"`，就是一次
 * 由存储决定做出的 wire 改动，故这里把规则钉住：规范十进制文本写成数字，其余写成它本来的文本，中间一处也不改写。
 */
class ResultIdsWireShapeTest {

    @Test
    void aCanonicalIntegerIsStillANumberOnTheWire() {
        assertEquals(42L, ResultIds.wire("42"), "an effect recorded before the change keeps its number");
        assertEquals(-7L, ResultIds.wire("-7"));
        assertEquals(0L, ResultIds.wire("0"));
    }

    @Test
    void aTextIdStaysText() {
        String uuid = "262de6a9-d59d-11f1-9e88-681def59d09d";
        assertEquals(uuid, ResultIds.wire(uuid), "a UUID is carried as itself");
        assertEquals("007", ResultIds.wire("007"), "and nothing is rewritten into a number it was not");
        assertEquals("12.5", ResultIds.wire("12.5"));
    }

    @Test
    void absenceStaysAbsence() {
        assertNull(ResultIds.wire(null));
        assertNull(ResultIds.asLong(null));
        assertNull(ResultIds.asLong("007"), "a leading zero is text, not the number it resembles");
    }
}
