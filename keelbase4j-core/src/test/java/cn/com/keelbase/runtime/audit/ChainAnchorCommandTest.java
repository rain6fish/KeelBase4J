// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The command's refusals — the cases where it must say "this is not something I can answer" rather
 * than print an empty or guessed result.
 *
 * <p>A one-shot command is easy to get wrong in the direction of being helpful: an unknown command
 * answered with the tail, or a comparison against a document with no `chains`, would look like an
 * answer and be none. Both are usage errors here, with the CLI's own exit code for them.
 *
 * 命令的拒绝 —— 那些必须说「这我答不了」、而不是打印一份空结果或猜出来的结果的场合。
 *
 * <p>一次性命令最容易错在「太想帮忙」的方向上：未知命令答成长尾、拿一份没有 `chains` 的文档去对照，看起来
 * 都像答案、其实都不是。这里两者都是用法错误，用 CLI 自己的退出码报出。
 */
class ChainAnchorCommandTest {

    @Test
    void anUnknownCommandIsAUsageError() {
        assertEquals(2, command("nope").execute());
    }

    @Test
    void compareWithoutAKnownRecordIsAUsageError() {
        assertEquals(2, command("compare").execute());
    }

    @Test
    void aDocumentWithoutChainsIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ChainAnchorCommand.tailsOf(Map.of("period", "daily")));

        assertTrue(refused.getMessage().contains("chains"), refused.getMessage());
    }

    /**
     * Both chain keys are required. Absence and an explicit {@code null} are different facts: only
     * the latter says "no chained row at that moment". A truncated record must not read as empty.
     *
     * 两个链键都必需。缺席与显式 `null` 是两个不同的事实：只有后者表示「该时刻该链没有已入链的行」。被
     * 截断的记录不能被读成「空链」。
     */
    @Test
    void aChainsObjectWithoutBothKeysIsRefused() {
        IllegalArgumentException noKeys = assertThrows(IllegalArgumentException.class,
                () -> ChainAnchorCommand.tailsOf(Map.of("chains", Map.of())));
        assertTrue(noKeys.getMessage().contains("aiAudit"), noKeys.getMessage());

        IllegalArgumentException noAi = assertThrows(IllegalArgumentException.class,
                () -> ChainAnchorCommand.tailsOf(Map.of("chains", Map.of("operationAudit", tail()))));
        assertTrue(noAi.getMessage().contains("aiAudit"), noAi.getMessage());

        IllegalArgumentException noOperation = assertThrows(IllegalArgumentException.class,
                () -> ChainAnchorCommand.tailsOf(Map.of("chains", Map.of("aiAudit", tail()))));
        assertTrue(noOperation.getMessage().contains("operationAudit"), noOperation.getMessage());
    }

    /**
     * The same document with both keys present and {@code null} is accepted: that is the schema's way
     * of saying the chain had no chained row, so refusing it would reject a legitimate payload.
     *
     * 同样的文档、两键都在且为 `null`，是接受的：那正是 schema 表达「该链没有已入链行」的方式，拒绝它
     * 会误伤合法载荷。
     */
    @Test
    void bothKeysPresentAsNullAreAccepted() {
        ChainAnchorService.Tails tails = ChainAnchorCommand.tailsOf(
                Json.parse("{\"chains\":{\"aiAudit\":null,\"operationAudit\":null}}"));

        assertNull(tails.aiAudit());
        assertNull(tails.operationAudit());
    }

    private static Map<String, Object> tail() {
        return Map.of("rows", 1, "lastId", 1, "lastHash", "a".repeat(64));
    }

    /**
     * The service is not reached by either refusal above, so a null stands in for it: a test that
     * needed a database to prove a usage error would be measuring something else.
     *
     * 上面两种拒绝都走不到服务，故用 null 代替它：一个要靠数据库才能证明用法错误的测试，量的是别的东西。
     */
    private static ChainAnchorCommand command(String name) {
        return new ChainAnchorCommand(null, name, "", "", false);
    }
}
