// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code AuditAction} is the contract's closed {@code action} vocabulary, and this pins it to the
 * vendored schema rather than trusting a copy.
 *
 * <p>The list is written in Java because a writer needs it without reading JSON at runtime; the only
 * thing that can then go wrong is that the two copies drift. So the comparison is exact <em>and</em>
 * two-way: a word the contract added and Java lacks fails, and so does a word Java kept after the
 * contract dropped it. Reading it through {@code WireSchemas.schemaFor("audit-payload")} means the
 * check follows the registry to whichever schema version the current contract pin names.
 *
 * <p>The behaviour below it is the half a caller sees: a known word comes back unchanged, an unknown
 * one is refused with its own type and names itself. {@code mcp_connect_test} is the concrete case —
 * it is the word this runtime used to write for a connect test before the host moved to the contract's
 * {@code analyze}, and it must be refused rather than silently accepted.
 *
 * `AuditAction` 就是契约那张**闭**的 `action` 词表，这里把它**钉在 vendored schema 上**，而不是相信一份抄本。
 *
 * <p>表写在 Java 里，是因为写入方需要它、而又不该在运行期读 JSON；于是唯一可能出错的，就是两份抄本漂开。
 * 故比较是**精确的、且双向的**：契约加了而 Java 没有的词会失败，契约删了而 Java 还留着的词同样会失败。经
 * {@code WireSchemas.schemaFor("audit-payload")} 读取，意味着这道检查会跟着登记表走到**当前契约 pin 所指的
 * 那一版 schema**。
 *
 * <p>其下的行为是调用方看得见的那一半：已知词原样返回，未知词以**自己的类型**被拒、并在消息里点自己的名。
 * `mcp_connect_test` 是那个具体的例子 —— 它是宿主改到契约词 `analyze` 之前，本运行时给一次连通测试写下的词，
 * 必须被**拒绝**、而不是被悄悄接受。
 */
class AuditActionTest {

    @Test
    void vocabularyIsExactlyTheContractsActionEnum() {
        Map<String, Object> schema = WireSchemas.schemaFor("audit-payload");
        Map<String, Object> action = Vectors.map(Vectors.map(schema.get("properties")).get("action"));
        List<?> contractEnum = Vectors.list(action, "enum");

        Set<String> contract = new LinkedHashSet<>();
        for (Object word : contractEnum) {
            contract.add(String.valueOf(word));
        }
        Set<String> java = new LinkedHashSet<>(AuditAction.VOCABULARY);

        assertEquals(contract.size(), java.size(),
                "the vocabulary has the same size as the contract's action enum");
        assertTrue(java.containsAll(contract), "every contract word is in AuditAction");
        assertTrue(contract.containsAll(java), "AuditAction carries no word the contract dropped");
    }

    @Test
    void analyzeIsOneOfTheWords() {
        assertTrue(AuditAction.isKnown("analyze"),
                "the host records a connect test as `analyze`, so the word must be on the vocabulary");
    }

    @Test
    void anOldWordIsNot() {
        assertFalse(AuditAction.isKnown("mcp_connect_test"),
                "a pre-contract word has left the vocabulary and must not pass as one of it");
        assertFalse(AuditAction.isKnown(null), "a null action is not a word");
        assertFalse(AuditAction.isKnown(""), "a blank action is not a word");
    }

    @Test
    void aKnownWordComesBackUnchanged() {
        for (String word : AuditAction.VOCABULARY) {
            assertEquals(word, AuditAction.require(word), "a known word is returned as it came in");
        }
    }

    @Test
    void anUnknownWordIsRefusedUnderItsOwnTypeAndNamesItself() {
        UnknownAuditActionException refused = assertThrows(UnknownAuditActionException.class,
                () -> AuditAction.require("mcp_connect_test"));

        assertEquals("mcp_connect_test", refused.action(), "the refusal carries the word it refused");
        assertTrue(refused.getMessage().contains("mcp_connect_test"),
                "the message names the refused word: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("analyze"),
                "the message lists the vocabulary, so a reader can pick a legal word");
    }
}
