// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectCompensator;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two reproduction cases for YD-46 — the revocation line's two blind spots. <b>Both encode the
 * criterion the roadmap asks for, so both are RED against today's code.</b> They are proof artefacts:
 * they are not a fix, and they are not meant to be merged as they stand.
 *
 * <p><b>① A failed compensation leaves no record of the attempt.</b> {@code SideEffectService.revoke}
 * moves the row, then appends the audit line. A compensator that throws rolls the whole transaction
 * back, so the effect stays {@code executed} and the chain gains nothing: "tried to revoke and failed"
 * and "never tried" read the same. The case here uses the real {@code PmTaskCompensator}: its target is
 * missing, so it throws {@code 404 NOT_FOUND} — a failure any deployment can produce with data drift.
 *
 * <p><b>② A cross-tenant revoke silently claims success.</b> {@code SideEffect} carries no tenant, and
 * the host compensator runs under the tenant of the person revoking ({@code YudaoCallerTenant.current()}
 * in {@code KeelBase-YuDao}'s {@code YudaoLocalCompensators.as}), not the tenant the write ran in. A
 * tenant-scoped delete therefore matches no row, returns normally, and the ledger says {@code revoked}.
 * The compensator in this test models exactly that host shape: rows are partitioned by tenant, and the
 * delete only reaches a row whose tenant equals the current one.
 *
 * <p>两条 YD-46 撤销线盲区的复现用例。**两者都写成路线图要求的判据，故对今天的代码是红的。**它们是**证据件**，
 * 不是修复，也不是按现状就该合的代码。
 *
 * <p>**① 补偿失败不留尝试的痕迹。** {@code SideEffectService.revoke} 先动行、后写审计行。补偿器抛异常会把
 * 整笔事务回滚，于是 effect 仍是 {@code executed}、链上什么也没多：「试过撤销、失败了」与「从没试过」读起来
 * 一样。这里的用例走真实的 {@code PmTaskCompensator}：它的目标不存在，故抛 {@code 404 NOT_FOUND} —— 任何部署
 * 遇到数据漂移都会发生的那种失败。
 *
 * <p>**② 跨租户撤销静默宣称成功。** {@code SideEffect} 没有租户列，而宿主补偿器跑在**撤销者**的租户下
 * （`KeelBase-YuDao` 的 `YudaoLocalCompensators.as` 读 `YudaoCallerTenant.current()`），不是那次写所在的租户。
 * 于是受租户约束的删除匹配不到任何行、正常返回，账本却说 {@code revoked}。本用例的补偿器正是照那个宿主形状建的：
 * 行按租户分区，删除只够得到与当前租户相等的那一行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(Yd46RevocationBlindSpotsTest.TenantModelConfiguration.class)
class Yd46RevocationBlindSpotsTest {

    private static final String TENANT_SCOPED_TYPE = "yd46_tenant_scoped_row";

    /**
     * The tenant a compensation call runs under, standing in for `YudaoCallerTenant.current()`. A thread
     * local for the same reason the host's holder is one: there is nowhere else to put it.
     *
     * 补偿调用所处的租户，代 `YudaoCallerTenant.current()`。用线程局部，理由与宿主那个持有者相同：别处没地方放。
     */
    private static final ThreadLocal<Long> COMPENSATION_TENANT = new ThreadLocal<>();

    /** The rows a tenant-scoped store holds: result id → the tenant that owns the row. */
    /** 受租户约束的存储持有的行：结果 id → 拥有这一行的租户。 */
    private static final Map<String, Long> ROWS = new ConcurrentHashMap<>();

    /**
     * Models the host's compensator shape: a delete under a tenant only reaches that tenant's row, and a
     * miss is not an exception — it is a normal return, which is what makes the ledger's `revoked` lie.
     *
     * 照宿主的补偿器形状：某租户下的删除只够得到该租户的行，而没够到**不抛异常** —— 它正常返回，这正是
     * 账本那句 `revoked` 成为假话的原因。
     */
    static class TenantScopedCompensator implements SideEffectCompensator {

        @Override
        public String resultType() {
            return TENANT_SCOPED_TYPE;
        }

        @Override
        public void compensate(SideEffect effect) {
            Long revokeTenant = COMPENSATION_TENANT.get();
            ROWS.computeIfPresent(effect.getResultId(),
                    (id, writeTenant) -> writeTenant.equals(revokeTenant) ? null : writeTenant);
        }
    }

    @TestConfiguration
    static class TenantModelConfiguration {

        @Bean
        TenantScopedCompensator tenantScopedCompensator() {
            return new TenantScopedCompensator();
        }
    }

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    SideEffectRepository effects;

    @Autowired
    AuditLogRepository audit;

    @Test
    void aFailedCompensationLeavesNoRecordOfTheAttempt() {
        Principal alice = new Principal("alice", "user");
        // A pm_task effect whose target no longer exists — the compensator throws 404, as it would on a
        // deployment after the row was deleted by other means.
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
                () -> assertEquals(auditBefore + 1, auditAfter,
                        "a revoke that was attempted and failed must leave a record; today it leaves "
                                + "none, so 'failed' and 'never tried' read the same"),
                () -> assertEquals("revoke_failed", after.getRevokeStatus(),
                        "the effect must say the attempt failed; today it still says executed"));
    }

    @Test
    void aCrossTenantRevokeDoesNotSilentlyClaimSuccess() {
        long writeTenant = 1L;
        long revokeTenant = 2L;
        String resultId = "row-" + UUID.randomUUID();
        Principal alice = new Principal("alice", "user");

        ROWS.put(resultId, writeTenant);
        try {
            COMPENSATION_TENANT.set(writeTenant);
            SideEffect effect = sideEffects.record(alice, "create_tenant_row", TENANT_SCOPED_TYPE,
                    resultId, "{\"probe\":\"" + UUID.randomUUID() + "\"}", "local_compensate");

            // The revoke arrives under a different tenant than the write did. The effect carries no
            // tenant, so nothing on this path can tell the compensator which one to use.
            // 撤销在**与那次写不同**的租户下到达。effect 上没有租户，故这条路上没有任何东西能告诉补偿器该用哪个。
            COMPENSATION_TENANT.set(revokeTenant);
            AtomicReference<RuntimeException> thrown = new AtomicReference<>();
            try {
                sideEffects.revoke(effect.getId(), alice);
            } catch (RuntimeException refused) {
                thrown.set(refused);
            }

            SideEffect after = effects.findById(effect.getId()).orElseThrow();
            assertAll(
                    () -> assertTrue(thrown.get() != null || !ROWS.containsKey(resultId),
                            "a cross-tenant revoke must either compensate under the effect's own "
                                    + "tenant or refuse; today it returns normally and the row survives"),
                    () -> assertFalse("revoked".equals(after.getRevokeStatus()),
                            "and it must not claim `revoked` when nothing was compensated"));
        } finally {
            COMPENSATION_TENANT.remove();
            ROWS.remove(resultId);
        }
    }
}
