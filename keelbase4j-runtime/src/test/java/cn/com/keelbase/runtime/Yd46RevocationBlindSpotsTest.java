// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * YD-46, blind spot ①: a revocation that was attempted and failed must be distinguishable from one
 * that was never attempted — on the effect row <b>and</b> on the audit chain.
 *
 * <p>Before the repair, {@code SideEffectService.revoke} wrote its audit line only after the
 * compensation had succeeded, so a compensator that threw rolled the whole transaction back: the
 * effect stayed {@code executed} and the chain gained nothing. The repair keeps the success path
 * exactly as it was and records the failed attempt in a transaction of its own, because the
 * attempt's transaction is the one rolling back. Two records, two places: the effect row carries
 * {@code revoke_status = revoke_failed}, and the chain gets an {@code effect_revoke} row whose
 * {@code outcome} is {@code failed} rather than {@code revoked}. Both are outside the failed
 * transaction, so neither is rolled back with it.
 *
 * <p><b>The chain's two readings are the same action, different outcomes.</b> A successful revoke
 * writes {@code effect_revoke + revoked} ({@code isError=false}); a failed attempt writes
 * {@code effect_revoke + failed} ({@code isError=true}); "never attempted" writes nothing. That is
 * the distinction the old shape could not carry.
 *
 * <p><b>The tenant half of YD-46 (blind spot ②) is not here.</b> It lives in the host: core has no
 * tenant concept, and the fix resolves the effect owner's tenant inside {@code KeelBase-YuDao}'s
 * compensation seam. The host test is the one that proves it; a core-level model could only restate
 * the absence of a column.
 *
 * <p>YD-46 盲区①：**试过但失败**的撤销必须与**从未尝试**区分得开 —— 既在 effect 行上，也在审计链上。
 *
 * <p>修复之前，{@code SideEffectService.revoke} 只在补偿成功之后才写审计行，故补偿器一抛异常，整笔事务
 * 回滚：effect 仍是 {@code executed}，链上什么也不多。修复保持成功路径原样，并用**独立事务**记下失败尝试
 * —— 因为正在回滚的正是那次尝试的事务。两处记录：effect 行带 {@code revoke_status = revoke_failed}；
 * 链上多一行 {@code effect_revoke}，其 {@code outcome} 是 {@code failed} 而不是 {@code revoked}。两者都在
 * 失败事务之外，故都不会跟着回滚。
 *
 * <p>**链上的两种读数：同一个 action、不同 outcome。** 成功的撤销写 {@code effect_revoke + revoked}
 * （{@code isError=false}）；失败的尝试写 {@code effect_revoke + failed}（{@code isError=true}）；
 * 「从未尝试」什么也不写。这正是旧形状装不下的区分。
 *
 * <p>**YD-46 的租户那一半（盲区②）不在这里。** 它在宿主：core 没有租户概念，修复在 `KeelBase-YuDao`
 * 的补偿缝里解析 effect 属主的租户。证明它的是宿主那边的测试；core 级的模型只能复述「没有那一列」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Yd46RevocationBlindSpotsTest {

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    SideEffectRepository effects;

    @Autowired
    AuditLogRepository audit;

    @Test
    void aFailedCompensationLeavesAFailedRowOnTheChainAndOnTheEffect() {
        Principal alice = new Principal("alice", "user");
        // A pm_task effect whose target no longer exists — the compensator throws 404, as it would on
        // a deployment after the row was deleted by other means.
        // 一个目标已不存在的 pm_task effect —— 补偿器抛 404，与部署上那一行被别的途径删掉后一样。
        SideEffect effect = sideEffects.record(alice, "create_pm_task", "pm_task",
                "999999999", "{\"probe\":\"" + UUID.randomUUID() + "\"}", "local_compensate");
        long auditBefore = audit.count();

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> sideEffects.revoke(effect.getId(), alice));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());

        long auditAfter = audit.count();
        Optional<AuditLog> latest = audit.findTopByOrderByIdDesc();
        SideEffect after = effects.findById(effect.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(auditBefore + 1, auditAfter,
                        "an attempted revocation that failed must leave a chain row; otherwise "
                                + "'tried and failed' and 'never tried' read the same"),
                () -> assertTrue(latest.isPresent(),
                        "a failed attempt must leave a chain row to name it"));
        AuditLog line = latest.orElseThrow();
        assertAll(
                () -> assertEquals("effect_revoke", line.getAction(),
                        "the row is the contract's revocation action, not a flattened tool call"),
                () -> assertEquals("failed", line.getOutcome(),
                        "and its outcome says the attempt failed, where a success says `revoked`"),
                () -> assertTrue(line.isError(),
                        "and it is marked as one of the calls that did not go as asked"),
                () -> assertTrue(line.getDetail().contains("failed"),
                        "the free-text detail names it too: " + line.getDetail()),
                () -> assertEquals("revoke_failed", after.getRevokeStatus(),
                        "the effect says the attempt failed, so 'tried and failed' differs from "
                                + "'never tried'"));
    }
}
