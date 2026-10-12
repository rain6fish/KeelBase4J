// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.runtime.authz.RuleDeniedException;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * A thrown execution frees its key only when the throw <em>proves</em> nothing landed.
 *
 * <p>The pre-execution claim is what makes two identical writes not both happen, and the engine
 * releases it when an attempt fails — because a key held by a failure nobody can retry is a call that
 * can never be made again. But "it threw" is not the same as "nothing was written": the tool is where
 * the write happens, so a tool can write and then fail, and freeing the key on that leaves the next
 * attempt free to write a <em>second</em> row. The ledger probe cannot catch it either — the effect is
 * only recorded after {@code execute} returns, so a tool that threw never recorded one.
 *
 * <p>So the engine's catch keeps doing two different things at two widths. It stays <b>broad for the
 * audit</b> — any throw is an event worth a line, and narrowing that is the defect the audit line was
 * added to fix. It is now <b>narrow for the release</b>: only a refusal raised before the tool does
 * anything frees the key, and everything else holds it until the lease runs out. Five minutes is
 * recoverable; a second write is not.
 *
 * <p>Both directions are asserted, because a fix that stopped releasing altogether would pass the
 * second case and quietly reintroduce the problem the release exists for.
 *
 * 一次抛出**只在它能证明什么都没落地时**放走键。
 *
 * <p>执行前的领取，是「两次相同的写不会都发生」的来路；而引擎在一次尝试失败时释放它——因为一个被
 * 「没人能重试的失败」占住的键，等于一个**永远做不成的调用**。但「它抛了」**不等于**「什么都没写」：工具
 * 正是写发生的地方，所以工具可以写了才失败，而按那个放走键，下一次尝试就**可以再写一行**。账本探测也接不住
 * ——effect 只在 `execute` 返回**之后**才记，抛出的工具从没记过。
 *
 * <p>于是引擎那个 catch 现在以**两种宽度**做两件事：**审计照旧放宽**——任何抛出都是一个值一行的事件，把它
 * 收窄正是那条审计行被加进来要修的缺陷；**释放收窄**——只有**在工具做任何事之前**抛出的拒绝才放走键，其余
 * 一切把键留到租约跑完。**五分钟可恢复，写第二次不可恢复**。
 *
 * <p>两个方向都断言，因为一个「干脆不释放」的修法会通过第二个用例、却悄悄把「释放」本来要解决的那个问题
 * 又请回来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AThrownCallOnlyFreesItsKeyWhenNothingLandedTest {

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    WriteClaimRepository claims;

    @TestConfiguration
    static class Probe {

        /** Refuses the way the row gate does: before the tool does anything. */
        @Bean
        AiTool probeDeniedTool() {
            return new ProbeTool("probe_denied") {
                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    throw new RuleDeniedException("casl", "该行不在你的数据范围内");
                }
            };
        }

        /** Fails without proving anything: a write that threw on its way through. */
        @Bean
        AiTool probeBrokenTool() {
            return new ProbeTool("probe_broken") {
                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    throw new IllegalStateException("the tool wrote, and then failed");
                }
            };
        }
    }

    /**
     * A write double: {@code R3} so it runs at an approval, and a result type because that is what makes
     * the engine take a claim at all — a tool that writes nothing is never claimed.
     *
     * 一个写的测试替身：`R3`，故它在被批准时运行；且带一个结果类型——那正是引擎会取领取的前提：什么都不写的
     * 工具从不占位。
     */
    abstract static class ProbeTool implements AiTool {

        private final String name;

        ProbeTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test double: a write that throws";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "probe_" + name;
        }
    }

    @Test
    void aDenialBeforeAnyWriteFreesTheKeySoTheCallCanBeMadeAgain() {
        assertThrows(RuleDeniedException.class, () -> approve("probe_denied"),
                "the refusal must still be a refusal — recorded, not swallowed");
        assertEquals(WriteClaim.RELEASED, claimStatus("probe_denied"),
                "a denial is raised before the tool does anything, so the key is free again");

        assertThrows(RuleDeniedException.class, () -> approve("probe_denied"),
                "and the next attempt runs, rather than being turned away as a repeat of a call that "
                        + "never happened");
    }

    @Test
    void aThrowThatProvesNothingKeepsTheKey() {
        assertThrows(IllegalStateException.class, () -> approve("probe_broken"));
        assertEquals(WriteClaim.CLAIMED, claimStatus("probe_broken"),
                "a throw from the tool does not prove nothing landed, so the key stays taken");

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> approve("probe_broken"),
                "so the next identical call is refused as a repeat rather than run a second time");
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    /** Propose the call and answer its own confirmation, so the tool runs and throws. */
    private void approve(String toolName) {
        ExecutionOutcome pending = engine.execute(toolName, Map.of(), ALICE);
        assertNotNull(pending.token(), "an R3 proposal waits for a person");
        engine.approve(pending.token(), ALICE);
    }

    private String claimStatus(String toolName) {
        String key = WriteClaimService.keyFor(ALICE, toolName, CanonicalJson.json(Map.of()));
        return claims.findByIdempotencyKey(key).map(WriteClaim::getStatus).orElse(null);
    }
}
