// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.authz.RuleDeniedException;
import cn.com.keelbase.runtime.web.WireExceptionAdvice;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a refusal puts on the wire when it knows what it refused.
 *
 * <p>The row gate deliberately claims no {@code deniedBy} — the frozen {@code permission-decision}
 * defines no basis for "this row is not yours". That absence used to take the whole
 * {@code explanation} with it, so a caller was told "forbidden" and nothing else even though the
 * guard knew the subject and the action. The three cases below pin the new line: everything that is
 * known is written, and the missing basis is expressed by the key being absent rather than by the
 * explanation being dropped.
 *
 * <p>拒绝在知道「拒了什么」时往线缆上放什么。
 *
 * <p>行闸**刻意不声称** `deniedBy`——冻结的 `permission-decision` 不为「这一行不是你的」定义依据。过去这个缺席
 * 把整个 `explanation` 一起带走了，于是调用方只被告知「forbidden」，尽管守卫明明知道主体与动作。下面三例钉住新的
 * 分界线：**知道的全写出来**，而缺失的依据由「那个键不在」来表达，不是由「整段解释被丢掉」来表达。
 */
class RuleDeniedExplanationTest {

    private static final String ROW_REASON = "该行不在你的数据范围内";

    private final WireExceptionAdvice advice = new WireExceptionAdvice();

    @Test
    void rowDenialNamesSubjectAndActionAndClaimsNoBasis() {
        Map<String, Object> body = decide(new RuleDeniedException(
                null, ROW_REASON, "CrmCustomer", "update", null));

        assertEquals(403, body.get("code"));
        assertEquals("ROW_OUT_OF_SCOPE", body.get("errorCode"));
        Map<String, Object> explanation = assertInstanceOf(Map.class, body.get("explanation"));
        assertEquals(ROW_REASON, explanation.get("reason"));
        assertEquals("CrmCustomer", explanation.get("subject"));
        assertEquals("update", explanation.get("action"));
        // The absence is the statement: no basis is claimed for a row denial.
        // 缺席本身即是那句话：行拒绝不声称依据。
        assertFalse(explanation.containsKey("deniedBy"));
    }

    @Test
    void ruleDenialStillCarriesItsBasis() {
        Map<String, Object> body = decide(new RuleDeniedException(
                "casl", "当前身份无权执行此操作", "AuditLog", "read", null));

        assertEquals("FORBIDDEN_RULE", body.get("errorCode"));
        Map<String, Object> explanation = assertInstanceOf(Map.class, body.get("explanation"));
        assertEquals("casl", explanation.get("deniedBy"));
        assertEquals("AuditLog", explanation.get("subject"));
        assertEquals("read", explanation.get("action"));
    }

    @Test
    void aRefusalWithNothingToExplainEmitsNoExplanation() {
        Map<String, Object> body = decide(new RuleDeniedException(null, ROW_REASON));

        assertEquals("ROW_OUT_OF_SCOPE", body.get("errorCode"));
        assertNull(body.get("explanation"));
        assertTrue(body.containsKey("errorCode"));
    }

    private Map<String, Object> decide(RuleDeniedException denied) {
        return advice.ruleDenied(denied).getBody();
    }
}
