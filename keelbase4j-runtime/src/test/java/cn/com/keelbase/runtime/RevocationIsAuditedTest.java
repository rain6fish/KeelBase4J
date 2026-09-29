// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * A revocation reaches the audit chain, and names the person who did it (seam S10).
 *
 * <p>Revoking used to write the ledger row and the soft delete and nothing else, while every other
 * governance transition — pending, approved, executed, refused — wrote a line. So the chain described
 * a follow-up that was written and never that somebody undid it, and the explicit question this job
 * has to answer, "who made the AI do what", could not be answered for the one operation whose whole
 * point is that a human intervened. Measured on a host before this test existed: the audit count was
 * unchanged across a revoke while both other tables moved.
 *
 * <p>The second case is the one that decides where the line goes: an operator revoking somebody else's
 * effect must be recorded <em>as the operator</em>. Recording the effect's owner would produce a row
 * that reads as if the person whose write was undone had undone it themselves.
 *
 * <p>撤销会进审计链，并**点名是谁做的**（缝 S10）。
 *
 * <p>过去撤销只写账本行与软删、别的什么都不写，而其它每一次治理迁移——pending、approved、executed、
 * refused——都写了行。于是链只描述一条被写下的跟进、从不描述有人把它撤了，而本轮必须回答的那个显式问题
 * 「谁让 AI 做了什么」，在**唯一一个其全部意义就是有人介入了**的操作上答不出来。**先实测、后有此测试**：
 * 一次撤销前后审计行数不变，而另两张表都动了。
 *
 * <p>第二个用例决定的是**这一行写给谁**：管理员撤销别人的副作用时，必须被记成**那个管理员**。若记成
 * effect 的 owner，就会产出一行读起来像「被撤的人自己撤的」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RevocationIsAuditedTest {

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    AuditLogRepository audit;

    @Autowired
    SideEffectRepository effects;

    @Test
    void aRevocationIsAnEventAndNamesTheActor() {
        Principal alice = new Principal("alice", "user");
        SideEffect effect = anEffectOf(alice);

        long before = audit.count();
        sideEffects.revoke(effect.getId(), alice);

        assertEquals(before + 1, audit.count(), "the revocation gets a line of its own");
        AuditLog line = audit.findTopByOrderByIdDesc().orElseThrow();
        assertEquals("alice", line.getUserId(), "and the line says who undid it");
        assertEquals("tool_call", line.getAction(), "in the frozen action vocabulary, which has no 'revoke'");
        assertTrue(line.getDetail().contains("revoked"),
                "and says what happened: " + line.getDetail());
    }

    @Test
    void anOperatorRevokingSomebodyElsesEffectIsNamedAsTheActor() {
        Principal bob = new Principal("bob", "user");
        Principal carolTheOperator = new Principal("carol", "admin");
        SideEffect effect = anEffectOf(bob);

        sideEffects.revoke(effect.getId(), carolTheOperator);

        AuditLog line = audit.findTopByOrderByIdDesc().orElseThrow();
        assertEquals("carol", line.getUserId(),
                "the operator is the actor; recording bob would read as if he had undone his own write");
        // Re-read rather than trusting the instance handed back by record(): revoke loads its own, and
        // asserting on the stale one would pass or fail for reasons that have nothing to do with the fix.
        assertEquals("revoked", effects.findById(effect.getId()).orElseThrow().getRevokeStatus());
    }

    /** An effect of this user's, with the row it points at — what a revoke needs to have something to do. */
    private SideEffect anEffectOf(Principal user) {
        Long target = followUps.save(new FollowUp(1L, "note for " + user.userId(), null, user.userId())).getId();
        return sideEffects.record(user, "create_followup", "follow_up", target, "{}", "local_compensate");
    }
}
