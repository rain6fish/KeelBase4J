// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The typed key's spelling has one home, and this answers to it.
 *
 * <p>The writer of ` · object MCP connection #12` lives in another repository, and so does the console
 * that reads it back. Neither can import the other's source, so the spelling used to live in three
 * places that merely happened to agree: the runtime's matcher, the host's writer, the console's reader.
 * `conformance/typed-object-key.json` is now the one definition, and this test is the runtime's answer
 * to it — every pair and every row in that file, asserted against the matcher that actually serves the
 * read face. A spelling change on the runtime side cannot pass this test by also changing the test:
 * the expected values come from the file.
 *
 * 带类型键的拼写只有一个家，而这里按它作答。
 *
 * <p>` · object MCP connection #12` 的**写入方**在另一个仓，把它读回来的**控制台**也在另一个仓：谁也
 * import 不到谁的源码，于是这条拼写过去住在三个**恰好一致**的地方 —— 运行时的匹配器、宿主的写入方、
 * 控制台的读取器。现在 `conformance/typed-object-key.json` 是那唯一一份定义，而本测试是运行时对它的回答：
 * 文件里的每一对、每一行都拿**真正服务读面**的那个匹配器断言一遍。运行时单方面改拼写，不能靠在测试里也
 * 改一遍蒙过去 —— 期望值来自那个文件。
 */
class TypedObjectKeyTest {

    private static Map<String, Object> definition;

    @BeforeAll
    static void readTheDefinition() throws IOException {
        try (InputStream in = TypedObjectKeyTest.class.getResourceAsStream("/typed-object-key.json")) {
            assertNotNull(in, "the core build must copy conformance/typed-object-key.json onto the "
                    + "classpath");
            definition = map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        assertTrue(Boolean.TRUE.equals(definition.get("endAnchored")),
                "the key is at the end of the sentence or it is not a key");
        Map<String, Object> tolerance = map(definition.get("tolerance"));
        assertEquals(Boolean.FALSE, tolerance.get("trailingWhitespace"),
                "a trailing space is not the end of the key");
        assertEquals(Boolean.FALSE, tolerance.get("trailingPunctuation"),
                "a trailing period is not the end of the key");
    }

    /**
     * Every pair in the definition spells exactly the suffix the definition says it does.
     *
     * 定义里的每一对，拼出的正是定义说它拼出的那个后缀。
     */
    @Test
    void everyPairSpellsWhatTheDefinitionSays() {
        for (Object raw : list(definition.get("pairs"))) {
            Map<String, Object> pair = map(raw);
            String name = (String) pair.get("name");
            String kind = (String) pair.get("kind");
            String id = pair.get("id") == null ? null : String.valueOf(pair.get("id"));
            Object expected = pair.get("suffix");
            assertEquals(expected, AuditService.typedObjectKeySuffix(kind, id),
                    "the definition's own spelling, not the code's, decides: " + name);
        }
    }

    /**
     * A row answers a search only when its sentence ends with that key — which is the whole of the
     * criterion, and the part the console used to be more forgiving about.
     *
     * 一行只有在**句末**正是那个键时才回答这次检索 —— 这就是判据的全部，也是控制台过去更宽容的那部分。
     */
    @Test
    void aRowMatchesOnlyWhenItsSentenceEndsWithThatKey() {
        Map<String, String> wordByType = new java.util.HashMap<>();
        for (Object raw : list(definition.get("kinds"))) {
            Map<String, Object> kind = map(raw);
            wordByType.put((String) kind.get("resultType"), (String) kind.get("word"));
        }

        for (Object raw : list(definition.get("rows"))) {
            Map<String, Object> row = map(raw);
            String name = (String) row.get("name");
            String detail = (String) row.get("detail");
            Object resultType = row.get("resultType");
            String word = wordByType.getOrDefault(String.valueOf(resultType), "MCP connection");
            String suffix = AuditService.typedObjectKeySuffix(word, String.valueOf(row.get("resultId")));

            if (resultType == null) {
                // The sentence does not end with any registered kind's key — a trailing space, a
                // period, a line break, more text, or a word that is not a kind at all.
                // 句子不以任何已登记种类词的键收尾 —— 尾随空格、句号、换行、后续文字，或一个根本不是种类词
                // 的词。
                assertFalse(matchesAnyKind(detail, wordByType),
                        "a row that does not end with a registered key must not match: " + name);
            } else {
                assertNotNull(suffix, "a row with a result type must build a key: " + name);
                assertTrue(detail.endsWith(suffix), "the row must end with its own key: " + name);
            }
        }
    }

    /**
     * The definition's id grammar is the runtime's: ASCII digits, one to eighteen, leading zeros kept.
     *
     * 定义里的主键文法就是运行时的：ASCII 数字、1 到 18 位、前导零保留。
     */
    @Test
    void theIdGrammarIsTheDefinitions() {
        assertNotNull(AuditService.typedObjectKeySuffix("MCP connection", "123456789012345678"),
                "eighteen digits are a key");
        assertNull(AuditService.typedObjectKeySuffix("MCP connection", "1234567890123456789"),
                "nineteen digits are not a key");
        assertNull(AuditService.typedObjectKeySuffix("MCP connection", "-1"),
                "a sign is not a digit");
        assertNull(AuditService.typedObjectKeySuffix("MCP connection", "1 2"),
                "a space is not a digit");
        assertEquals(" · object MCP connection #007",
                AuditService.typedObjectKeySuffix("MCP connection", "007"),
                "leading zeros are significant, so #007 is not #7");
    }

    private static boolean matchesAnyKind(String detail, Map<String, String> wordByType) {
        for (String word : wordByType.values()) {
            java.util.regex.Pattern key = java.util.regex.Pattern.compile(
                    // `\z`, not `$`: Java's `$` also matches before a final line terminator, while the
                    // runtime's `endsWith` does not — using `$` here would make the test accept the
                    // trailing-newline row the matcher rejects.
                    // 用 `\z` 而不是 `$`：Java 的 `$` 也匹配**末尾换行之前**，而运行时的 `endsWith`
                    // 不匹配 —— 用 `$` 会让测试接受匹配器拒绝的「尾随换行」那一行。
                    " · object " + java.util.regex.Pattern.quote(word) + " #[0-9]{1,18}\\z");
            if (key.matcher(detail).find()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
