// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * The service is not reached by either refusal above, so a null stands in for it: a test that
     * needed a database to prove a usage error would be measuring something else.
     *
     * 上面两种拒绝都走不到服务，故用 null 代替它：一个要靠数据库才能证明用法错误的测试，量的是别的东西。
     */
    private static ChainAnchorCommand command(String name) {
        return new ChainAnchorCommand(null, name, "", "", false);
    }
}
