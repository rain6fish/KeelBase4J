// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.Principal;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A tier-B grant on the ledger is necessary, not sufficient: the holder may close only the holds its
 * row range covers.
 *
 * <p>Delivery tier A never exposes this — only an administrator holds {@code ToolEffect:update}, and
 * an administrator's range is every row. The defect this fixes is a tier-B one: a host that grants a
 * non-administrator {@code update} on the ledger, scoped to its owner, used to hand that role the
 * ability to close <em>any</em> caller's hold, because the release path read the action and never the
 * row. The deployment here is that host: {@code bob} holds the grant with an owner field, {@code
 * alice} and {@code carol} keep the tier-A rules.
 *
 * <p>Both directions are asserted. Bob is refused alice's hold with {@code 403} and alice's row is
 * untouched; bob closes his own; carol, whose range is all rows, still closes alice's. Removing the
 * row gate turns the first assertion red, and widening it turns the second one red.
 *
 * 账本上的一道档 B 授予是**必要而不充分**的：持有者只能关闭其行范围够得到的占位。
 *
 * <p>档 A 从不会暴露这一点 —— 只有管理员持有 `ToolEffect:update`，而管理员的范围是每一行。本次修掉的缺陷
 * 是档 B 的：一个宿主把按主人收窄的 `update` 授给非管理员，那个角色此前能关掉**任何人**的占位，因为释放
 * 路径只读了动作、从未读行。这里的部署就是那个宿主：`bob` 带 owner 字段持有该授予，`alice` 与 `carol`
 * 保持档 A 规则。
 *
 * <p>两个方向都断言。bob 关 alice 的占位被 `403` 拒绝、且 alice 那一行原封不动；bob 关自己的成功；
 * `carol` 范围是全部行，仍能关 alice 的。去掉行闸会让第一条变红，放宽它会让第二条变红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ClaimReleaseTenantBoundaryTest {

    private static final String TOOL = "create_followup";

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);

    private static final Principal BOB = new Principal("bob", Principal.ROLE_USER);

    @TestConfiguration
    static class OwnerScopedDeployment {

        /** The host's tier-B answer: bob keeps the tier-A rules and gains the owner-scoped grant. */
        @Bean
        AuthorizationRules ownerScopedRules() {
            return new AuthorizationRules() {
                @Override
                public List<Rule> rulesFor(Principal principal) {
                    if (!BOB.userId().equals(principal.userId())) {
                        return super.rulesFor(principal);
                    }
                    List<Rule> rules = new ArrayList<>(super.rulesFor(principal));
                    rules.add(new Rule(GovernanceReadSubjects.TOOL_EFFECT, "update", "userId"));
                    return rules;
                }
            };
        }
    }

    @Autowired
    WriteClaimService claims;

    @Autowired
    WriteClaimRepository claimRows;

    @Autowired
    AuditLogRepository auditRows;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void anOwnerScopedGrantClosesOnlyItsOwnHolds() {
        WriteClaim aliceHold = strandedHold(ALICE);
        WriteClaim bobHold = strandedHold(BOB);
        String refusedMarker = "note-" + UUID.randomUUID();
        String ownMarker = "note-" + UUID.randomUUID();

        ResponseEntity<String> refused = release("bob", aliceHold.getId(), refusedMarker);

        assertEquals(403, refused.getStatusCode().value(),
                "bob may act on the ledger, but alice's hold is not his to close: " + refused);
        assertEquals(WriteClaim.CLAIMED,
                claimRows.findById(aliceHold.getId()).orElseThrow().getStatus(),
                "a refused row gate moves nothing");
        assertEquals(0, rowsMentioning(refusedMarker), "and it writes no line");

        ResponseEntity<String> allowed = release("bob", bobHold.getId(), ownMarker);

        assertEquals(200, allowed.getStatusCode().value(), allowed.toString());
        assertEquals(WriteClaim.CLOSED_UNKNOWN,
                claimRows.findById(bobHold.getId()).orElseThrow().getStatus(),
                "his own hold is inside his range");
        assertEquals(1, rowsMentioning(ownMarker));
    }

    @Test
    void anAdministratorStillClosesAnyHold() {
        WriteClaim aliceHold = strandedHold(ALICE);
        String marker = "note-" + UUID.randomUUID();

        ResponseEntity<String> released = release("carol", aliceHold.getId(), marker);

        assertEquals(200, released.getStatusCode().value(),
                "the administrator's range is every row: " + released);
        assertEquals(WriteClaim.CLOSED_UNKNOWN,
                claimRows.findById(aliceHold.getId()).orElseThrow().getStatus());
        assertEquals(1, rowsMentioning(marker));
    }

    /** A claim taken for {@code owner}, aged past the lease so it is releasable. */
    private WriteClaim strandedHold(Principal owner) {
        String args = "{\"note\":\"" + UUID.randomUUID() + "\"}";
        assertTrue(claims.claim(owner, TOOL, args).won());
        WriteClaim hold = claimRows
                .findByIdempotencyKey(WriteClaimService.keyFor(owner, TOOL, args))
                .orElseThrow();
        transactions.executeWithoutResult(status -> entityManager
                .createQuery("update WriteClaim c set c.claimedAt = :old where c.id = :id")
                .setParameter("old", Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 1_000L))
                .setParameter("id", hold.getId())
                .executeUpdate());
        return hold;
    }

    private ResponseEntity<String> release(String user, Long claimId, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", note);
        payload.put("evidenceRef", null);
        String body = CanonicalJson.json(payload);
        return rest.exchange(URI.create("/ai/tool-effects/claims/" + claimId + "/release"),
                HttpMethod.POST, new HttpEntity<>(body, headers(user)), String.class);
    }

    private long rowsMentioning(String marker) {
        return auditRows.findAll().stream()
                .filter(row -> "claim_release".equals(row.getAction()))
                .filter(row -> row.getDetail() != null && row.getDetail().contains(marker))
                .count();
    }

    private HttpHeaders headers(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return headers;
    }
}
