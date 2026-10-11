// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectCompensator;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The local compensation seam: a deployment's own {@code resultType} becomes revocable, and a type
 * with nothing registered is refused in a way that says what to register.
 *
 * <p>Before the seam, that path named one entity, so every other type ended in a conflict — and
 * because the conflict is thrown before the audit line, it left no trace at all. The unregistered
 * case here is what keeps the registered one honest: if an unregistered type quietly produced a row,
 * "there is a path" and "there is not" would read the same from outside, which is exactly the
 * difference between PASS and SKIP on the rate that counts revocations.
 *
 * 本地补偿这条缝：部署自己的 {@code resultType} 变得可撤销，而什么都没注册的类型，以一句**点名该注册什么**的话
 * 被拒。
 *
 * <p>这条缝之前，那条路只点名一个实体，别的类型一律以冲突收场 —— 而那个冲突抛在审计行**之前**，什么都不留。
 * 本类里「没注册」那个用例正是让「已注册」那个**站得住**的东西：若一个没注册的类型悄悄产出一行，那么「有路」
 * 与「没路」从外面读起来就一样了 —— 而那正是「按撤销数算的那个率」上 `PASS` 与 `SKIP` 的差别。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(LocalCompensationSeamTest.SeamTestConfiguration.class)
class LocalCompensationSeamTest {

    /** A type this runtime knows nothing about, standing in for a deployment's own entity. */
    private static final String REGISTERED_TYPE = "deployment_row";

    /** A second one, with nothing registered against it. */
    private static final String UNREGISTERED_TYPE = "nobody_compensates_this";

    /** What a deployment would write, and the only reason we can tell it ran. */
    static class RecordingCompensator implements SideEffectCompensator {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String resultType() {
            return REGISTERED_TYPE;
        }

        @Override
        public void compensate(SideEffect effect) {
            calls.incrementAndGet();
        }
    }

    @TestConfiguration
    static class SeamTestConfiguration {

        @Bean
        RecordingCompensator recordingCompensator() {
            return new RecordingCompensator();
        }
    }

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    SideEffectRepository effects;

    @Autowired
    AuditLogRepository audit;

    @Autowired
    RecordingCompensator compensator;

    @Test
    void aRegisteredCompensatorMakesItsOwnTypeRevocable() {
        Principal alice = new Principal("alice", "user");
        SideEffect effect = anEffectOf(alice, REGISTERED_TYPE);
        long before = audit.count();

        sideEffects.revoke(effect.getId(), alice);

        assertEquals(1, compensator.calls.get(), "the deployment's own compensator ran");
        assertEquals("revoked", effects.findById(effect.getId()).orElseThrow().getRevokeStatus(),
                "and a type with a path reaches `revoked` — which is what turns the bar to PASS");
        assertEquals(before + 1, audit.count(), "the revocation still gets a line of its own");
        AuditLog line = audit.findTopByOrderByIdDesc().orElseThrow();
        assertEquals("effect_revoke", line.getAction());
        assertTrue(line.getDetail().contains("revoked"), "and says what happened: " + line.getDetail());
    }

    @Test
    void anUnregisteredTypeIsRefusedAndLeavesNoTraceOfTheAttempt() {
        Principal alice = new Principal("alice", "user");
        SideEffect effect = anEffectOf(alice, UNREGISTERED_TYPE);
        long before = audit.count();

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> sideEffects.revoke(effect.getId(), alice));

        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        // The message is the whole point of refusing here rather than falling back to "compensating":
        // whoever reads it is told what to register, so the refusal is a next step rather than a wall.
        assertTrue(refused.getReason().contains("SideEffectCompensator"),
                "the refusal names what is missing: " + refused.getReason());
        assertTrue(refused.getReason().contains(UNREGISTERED_TYPE),
                "and which type it is missing for: " + refused.getReason());
        assertEquals(before, audit.count(),
                "and nothing was written — an attempt that leaves a line would read as a path that exists");
        assertEquals("executed", effects.findById(effect.getId()).orElseThrow().getRevokeStatus(),
                "the effect is untouched: neither revoked nor left half-compensated");
    }

    @Test
    void twoCompensatorsForOneTypeFailAtStartupRatherThanChoosingSilently() {
        SideEffectCompensator first = new RecordingCompensator();
        SideEffectCompensator second = new RecordingCompensator();

        // The repositories are null because the clash is found while the constructor is still wiring
        // fields: nothing downstream is reached, which is the behaviour being asserted.
        //
        // 两个仓储传 null，是因为这个冲突在构造子**还在接字段时**就被发现：后面一行都走不到 —— 而「走不到」
        // 正是这里要断言的行为。
        IllegalStateException clash = assertThrows(IllegalStateException.class,
                () -> new SideEffectService(null, null, null, List.of(first, second)));

        assertTrue(clash.getMessage().contains(REGISTERED_TYPE), clash.getMessage());
        assertInstanceOf(IllegalStateException.class, clash);
    }

    /**
     * An effect of this user's, of a type the caller names — a revoke needs something to act on.
     *
     * <p>The type goes into the arguments on purpose: the idempotency key is user + tool + args and
     * does <em>not</em> include the result type, so two cases written with identical arguments would
     * share one effect — and the second would be revoking the first's row.
     *
     * 类型特意编进参数里：幂等键是 user + tool + args，**不含**结果类型，故两条参数相同的用例会**共用**一个
     * effect —— 第二条撤的就是第一条那一行了。
     */
    private SideEffect anEffectOf(Principal user, String resultType) {
        return sideEffects.record(user, "some_write_tool", resultType, 1L,
                "{\"resultType\":\"" + resultType + "\"}", "local_compensate");
    }
}
