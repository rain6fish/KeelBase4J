// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.domain.PmProject;
import cn.com.keelbase.runtime.domain.PmProjectRepository;
import cn.com.keelbase.runtime.domain.PmTask;
import cn.com.keelbase.runtime.domain.PmTaskRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PM reference project's trust loop, end to end: R1 auto-executes, R3 waits for the operator, R4
 * waits for a second person, R5 is blocked, the audit chain records every outcome, and a revoke
 * compensates the write locally.
 *
 * <p>It is deliberately the same shape as {@link TrustLoopTest} over a second domain. The point of a
 * second reference project is that the governance is not a property of the CRM entities; if a test
 * here needed a different seam, the seam would be the finding.
 *
 * <p>PM 参考项目的信任闭环，端到端：R1 自动执行、R3 等操作者、R4 等第二个人、R5 被阻断、审计链记下每一种结局，
 * 而撤销在本地补偿那次写。
 *
 * <p>它刻意与 {@link TrustLoopTest} **同形**、只是换了一个域。第二个参考项目的意义正在于：治理**不是** CRM
 * 实体的属性；假如这里的测试需要另一条接缝，那条接缝本身就是发现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class PmReferenceLoopTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JsonMapper json;

    @Autowired
    PmProjectRepository projects;

    @Autowired
    PmTaskRepository tasks;

    @Autowired
    ConfirmationRequestRepository confirmations;

    @Autowired
    SideEffectRepository effects;

    @Autowired
    AuditLogRepository audit;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @BeforeEach
    void noLeftoversFromAnotherCase() {
        confirmations.deleteAll();
        effects.deleteAll();
    }

    @Test
    void aReadRunsAutomaticallyAndReportsADeadlineRisk() {
        Long project = project("Atlas Launch", "high", "alice");
        task(project, "Overdue", LocalDate.now().minusDays(2));
        task(project, "Due soon", LocalDate.now().plusDays(3));
        task(project, "Ignored when done", LocalDate.now().minusDays(30), true);

        int held = Pending.count(rest, "alice", delegationSecret);
        Map<String, Object> answer = chat("alice", "分析项目风险", project);

        assertTrue(String.valueOf(answer.get("reply")).contains("level=critical"),
                "the read ran and its deadline-risk reading is reported: " + answer.get("reply"));
        assertEquals(held, Pending.count(rest, "alice", delegationSecret),
                "a read is not gated, so it holds nothing back");
        assertTrue(((List<?>) answer.get("toolCalls")).contains("analyze_project_risk"),
                "the planner routed to the PM read tool: " + answer);
    }

    @Test
    void aWriteWaitsForTheOperatorAndARevokeCompensatesItLocally() {
        Long project = project("Atlas Launch", "low", "alice");
        int held = Pending.count(rest, "alice", delegationSecret);

        Map<String, Object> proposed = chat("alice", "为项目创建一个任务", project);
        assertEquals(held + 1, Pending.count(rest, "alice", delegationSecret),
                "an R3 write must be proposed and wait on the operator");
        assertTrue(String.valueOf(proposed.get("reply")).contains("waiting for your confirmation"),
                "and the reply says so instead of claiming the write happened: " + proposed.get("reply"));
        assertTrue(liveTasks(project).isEmpty(), "nothing may be written before confirmation");

        String token = Pending.token(rest, "alice", delegationSecret);
        ExecutionOutcome approved = confirm("alice", token, "approve", 200);
        assertEquals("executed", approved.status());
        assertNotNull(approved.effectId(), "a write must record a side effect");
        assertEquals(1, liveTasks(project).size());

        Map<?, ?> revoked = delete("/ai/tool-effects/" + approved.effectId(), "alice");
        assertEquals("revoked", revoked.get("revokeStatus"));
        assertTrue(liveTasks(project).isEmpty(), "revoke must remove the task from the live set");

        AuditLog line = audit.findTopByOrderByIdDesc().orElseThrow();
        assertEquals("effect_revoke", line.getAction(), "the revoke leaves a line of its own");

        ResponseEntity<Map> verify = rest.exchange("/audit/verify", HttpMethod.GET,
                entity("carol", null), Map.class);
        assertEquals(200, verify.getStatusCode().value());
        Map<String, Object> verifyData = Envelopes.data(verify.getBody());
        assertEquals(Boolean.TRUE, verifyData.get("valid"),
                "audit hash chain must verify after the revoke");
    }

    @Test
    void aHighImpactActionNeedsASecondPerson() {
        Long project = project("Atlas Launch", "low", "alice");
        int held = Pending.count(rest, "alice", delegationSecret);

        chat("alice", "升级项目", project);
        assertEquals(held, Pending.count(rest, "alice", delegationSecret),
                "an approval row waits on somebody else, so it is not listed for its initiator");
        assertTrue(liveTasks(project).isEmpty(), "nothing runs while the second person is being asked");

        // An approval row is deliberately not listed for its initiator: somebody else has to answer
        // it, so the token is read from the administrator's queue — the surface that can see it.
        //
        // 审批行**刻意不列给发起人**：得由别人来回答，故 token 从管理员队列读 —— 那是看得见它的面。
        String token = approvalToken();
        assertEquals(403, approveBy("alice", token, "approve").getStatusCode().value(),
                "the initiator is not an approver");
        assertTrue(liveTasks(project).isEmpty(), "a refused self-approval must not run the tool");

        ExecutionOutcome approved = json.convertValue(
                Envelopes.data(approveBy("carol", token, "approve").getBody()), ExecutionOutcome.class);
        assertEquals("executed", approved.status());
        List<PmTask> written = liveTasks(project);
        assertEquals(1, written.size());
        assertEquals("alice", written.get(0).getOwnerUserId(),
                "the write belongs to the initiator, not to whoever approved it");

        delete("/ai/tool-effects/" + approved.effectId(), "alice");
        assertTrue(liveTasks(project).isEmpty());
    }

    @Test
    void anInitiatorCannotApproveTheirOwnHighImpactActionEvenAsAnAdministrator() {
        Long project = project("Carol's project", "low", "carol");

        chat("carol", "升级项目", project);
        String token = approvalToken();

        assertEquals(403, approveBy("carol", token, "approve").getStatusCode().value(),
                "the rule is about who, not about what the caller may do elsewhere");
        assertTrue(liveTasks(project).isEmpty());
    }

    @Test
    void anIrreversibleToolIsBlockedAndAudited() {
        Long project = project("Atlas Launch", "low", "alice");
        int held = Pending.count(rest, "alice", delegationSecret);
        long effectCount = effects.count();

        Map<String, Object> answer = chat("alice", "删除项目", project);

        assertTrue(String.valueOf(answer.get("reply")).contains("blocked"),
                "the R5 tool is refused by the risk policy: " + answer.get("reply"));
        assertEquals(held, Pending.count(rest, "alice", delegationSecret),
                "a blocked call asks nobody for a confirmation");
        assertEquals(effectCount, effects.count(), "and it writes no side effect");
        assertTrue(projects.findById(project).isPresent(), "and the project is untouched");

        AuditLog line = audit.findTopByOrderByIdDesc().orElseThrow();
        assertTrue(line.isError());
        assertTrue(line.getDetail().contains("delete_project blocked"),
                "the block is on the audit chain: " + line.getDetail());
        assertTrue(line.getDetail().contains("risk_policy"),
                "and names the check that refused it: " + line.getDetail());
        assertEquals("blocked", line.getOutcome());
    }

    @Test
    void anotherUsersProjectIsRefusedAndAudited() {
        Long project = project("Atlas Launch", "low", "alice");

        ResponseEntity<Map> refused = rest.postForEntity("/ai/chat",
                entity("bob", Map.of("message", "分析项目风险", "targetId", project)), Map.class);

        assertEquals(403, refused.getStatusCode().value(),
                "cross-user access must be denied; body was " + refused.getBody());
        assertTrue(liveTasks(project).isEmpty(), "and the refusal wrote nothing");

        AuditLog line = audit.findTopByOrderByIdDesc().orElseThrow();
        assertTrue(line.isError());
        assertTrue(line.getDetail().contains("refused"), "the refusal is on the audit chain: "
                + line.getDetail());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private Long project(String name, String riskLevel, String owner) {
        PmProject project = new PmProject(name, "active", riskLevel, "sample", owner);
        project.assign(1L, "alice".equals(owner) ? 11L : 10L);
        return projects.save(project).getId();
    }

    private void task(Long projectId, String title, LocalDate dueDate) {
        task(projectId, title, dueDate, false);
    }

    private void task(Long projectId, String title, LocalDate dueDate, boolean done) {
        PmTask task = new PmTask(projectId, title, dueDate, "alice");
        task.assign(1L, 11L);
        if (done) {
            task.markDone();
        }
        tasks.save(task);
    }

    private List<PmTask> liveTasks(Long projectId) {
        return tasks.findByProjectIdAndDeletedAtIsNull(projectId);
    }

    private Map<String, Object> chat(String userId, String message, Long targetId) {
        ResponseEntity<Map> res = rest.postForEntity("/ai/chat",
                entity(userId, Map.of("message", message, "targetId", targetId)), Map.class);
        assertEquals(200, res.getStatusCode().value(), "POST /ai/chat");
        return Envelopes.data(res.getBody());
    }

    private ExecutionOutcome confirm(String userId, String token, String decision, int expectedStatus) {
        ResponseEntity<Map> res = rest.postForEntity(
                "/ai/confirmations/" + token,
                entity(userId, Map.of("decision", decision)),
                Map.class);
        assertEquals(expectedStatus, res.getStatusCode().value(), "POST /ai/confirmations");
        return json.convertValue(Envelopes.data(res.getBody()), ExecutionOutcome.class);
    }

    /**
     * The pending {@code escalate_project} token, read from the administrator's queue.
     *
     * <p>It is the surface that sees an approval row. The initiator's own list deliberately does not,
     * because a row waiting on somebody else is not waiting on them.
     *
     * 待确认的 {@code escalate_project} token，从管理员队列读。
     *
     * <p>那是看得见审批行的面。发起人自己的列表**刻意不列它** —— 一条在等别人的行，不是在等他。
     */
    @SuppressWarnings("unchecked")
    private String approvalToken() {
        ResponseEntity<Map> res = rest.exchange("/ai/confirmations?page=1&limit=100",
                HttpMethod.GET, entity("carol", null), Map.class);
        assertEquals(200, res.getStatusCode().value(), "GET /ai/confirmations");
        Map<String, Object> data = Envelopes.data(res.getBody());
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        String newest = null;
        String newestAt = null;
        for (Map<String, Object> item : items) {
            if (!"escalate_project".equals(item.get("toolName"))
                    || !"pending".equals(item.get("status"))) {
                continue;
            }
            String at = String.valueOf(item.get("createdAt"));
            if (newestAt == null || at.compareTo(newestAt) > 0) {
                newestAt = at;
                newest = String.valueOf(item.get("token"));
            }
        }
        assertNotNull(newest, "the approval row is visible to an administrator");
        return newest;
    }

    private ResponseEntity<Map> approveBy(String userId, String token, String decision) {
        return rest.postForEntity(
                "/ai/confirmations/" + token + "/approve-by",
                entity(userId, Map.of("decision", decision)),
                Map.class);
    }

    private Map<?, ?> delete(String path, String userId) {
        ResponseEntity<Map> res = rest.exchange(path, HttpMethod.DELETE, entity(userId, null), Map.class);
        assertEquals(200, res.getStatusCode().value(), "DELETE " + path);
        return Envelopes.data(res.getBody());
    }

    private HttpEntity<Object> entity(String userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        }
        return new HttpEntity<>(body, headers);
    }
}
