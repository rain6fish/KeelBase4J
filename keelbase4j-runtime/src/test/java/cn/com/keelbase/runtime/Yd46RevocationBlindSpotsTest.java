// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * YD-46, blind spot ①: a revocation that was attempted and failed must be distinguishable from one
 * that was never attempted.
 *
 * <p>Before the repair, {@code SideEffectService.revoke} wrote its audit line only after the
 * compensation had succeeded, so a compensator that threw rolled the whole transaction back: the
 * effect stayed {@code executed} and the chain gained nothing. The repair keeps the success path
 * exactly as it was and records the failed attempt on the effect itself
 * ({@code revoke_status = revoke_failed}) in a transaction of its own, because the attempt's
 * transaction is the one rolling back. The chain still gains no success-shaped line — that was the
 * old test's concern, and it remains true.
 *
 * <p><b>The tenant half of YD-46 (blind spot ②) is not here.</b> It lives in the host: core has no
 * tenant concept, and the fix resolves the effect owner's tenant inside {@code KeelBase-YuDao}'s
 * compensation seam. The host test is the one that proves it; a core-level model could only restate
 * the absence of a column.
 *
 * <p>YD-46 盲区①：**试过但失败**的撤销必须与**从未尝试**区分得开。
 *
 * <p>修复之前，{@code SideEffectService.revoke} 只在补偿成功之后才写审计行，故补偿器一抛异常，整笔事务
 * 回滚：effect 仍是 {@code executed}，链上什么也不多。修复保持成功路径原样，把失败尝试记在 effect 自己身上
 * （{@code revoke_status = revoke_failed}），并用**独立事务**写 —— 因为正在回滚的正是那次尝试的事务。链上
 * 仍然不多一条成功形状的行：那是旧测试的关切，而它仍然成立。
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
    void aFailedCompensationIsRecordedOnTheEffectNotAsASuccessRow() {
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
        SideEffect after = effects.findById(effect.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(auditBefore, auditAfter,
                        "no success-shaped audit line is written — the chain must not claim the "
                                + "revoke happened"),
                () -> assertEquals("revoke_failed", after.getRevokeStatus(),
                        "the effect says the attempt failed, so 'tried and failed' differs from "
                                + "'never tried'"));
    }
}
