// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.protocol.WireSchemas;
import cn.com.keelbase.runtime.audit.AuditService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * The chain can be read back: which rows a caller left, in a period, and only for those who may see
 * them.
 *
 * <p>Before this, the chain could be appended to and verified but not read. The repository holding
 * the rows was reachable only from inside its own package, so "which rows did this caller put on the
 * chain today" had no answer — the question that a run's operator asks first. This pins the three
 * things that make the answer usable rather than merely present: the filter actually narrows, the
 * period actually cuts, and the rows go only to an administrator.
 *
 * <p>The filter this deployment cannot apply is asserted too, and that one is about a failure mode
 * rather than a feature: the reference offers agent, organisation and denied views, this runtime
 * records none of those, and a filter accepted and silently not applied returns a list that looks
 * filtered and is not. Refusing is the only answer a caller can act on.
 *
 * 链能被读回来了：某个调用方在一段时间里留下了哪些行，且只给有权看见它们的人。
 *
 * <p>在此之前，链能追加、能校验，却**读不回来**。存着那些行的仓库只有**它自己那个包内**够得到，于是
 * 「这个调用方今天往链上留了哪几行」**没有答案** —— 而那是**一次运行的操作者最先问的问题**。这里钉住让答案
 * **可用**（而不只是**存在**）的三件事：筛选真的收窄了、时间段真的切了、行**只**给管理员。
 *
 * <p>本部署**施加不了**的那个筛选也一并断言了，而它讲的是一种**失效模式**、不是一项功能：参照实现还提供
 * agent、组织与「被拒」三个视图，本运行时**一个都不记**，而一个**被接受却悄悄没被施加**的筛选回出来的是一份
 * **看着像筛过、其实没有**的列表。**拒绝**是调用方唯一能据以行动的回答。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class AuditLogReadTest {

    /**
     * This deployment's administrator, and the reason the rows are readable at all.
     *
     * 本部署的管理员，也是这些行**之所以读得回来**的那个理由。
     */
    private static final String ADMIN = "carol";

    /**
     * An ordinary caller: they may act, and that does not make the chain a roster they may read.
     *
     * 一个普通调用方：他能动，而这**不**让这条链变成他可以读的花名册。
     */
    private static final String CALLER = "alice";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    private ResponseEntity<String> get(String path, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return rest.exchange(URI.create(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * The {@code data} of a successful call — the array of rows.
     *
     * 一次成功调用的 {@code data} —— 那个行数组。
     */
    private List<?> rows(String query, String user) {
        ResponseEntity<String> response = get("/audit/logs" + query, user);
        assertEquals(200, response.getStatusCode().value(), "the read must be served: " + response);
        return (List<?>) Envelopes.data(Vectors.map(Json.parse(response.getBody())));
    }

    @Test
    void anAdministratorReadsTheRowsOfOneCaller() {
        // A caller of this test's own, so the assertion is on rows this test put there and not on
        // whatever the rest of the suite left in a shared table.
        //
        // 一个本测试自己的调用方，故断言针对的是**本测试放进去的行**，而不是套件其余部分在**共用表**里留下的。
        String who = "reader-" + UUID.randomUUID();
        audit.append("tool_call", who, "first");
        audit.append("tool_call", who, "second");
        audit.append("tool_call", "other-" + UUID.randomUUID(), "someone else's row");

        List<?> found = rows("?userId=" + who, ADMIN);

        assertEquals(2, found.size(), "the filter must narrow the chain to one caller");
        // Newest first, which is how the reference's own listing orders the same rows.
        //
        // 最新的在前 —— 参照实现自己那份列表也按这个序排同样的行。
        assertEquals("second", ((Map<?, ?>) found.get(0)).get("detail"));
        assertEquals("first", ((Map<?, ?>) found.get(1)).get("detail"));
        for (Object row : found) {
            assertEquals(who, ((Map<?, ?>) row).get("userId"),
                    "no other caller's row may appear under this filter");
            WireSchemas.assertConformsTo(row, "ai-audit-log-row");
        }
    }

    @Test
    void aPeriodCutsTheRowsBothWays() {
        String who = "period-" + UUID.randomUUID();
        audit.append("tool_call", who, "recorded now");

        // Both sides, because a period filter that never excludes anything passes an "includes it"
        // assertion on its own.
        //
        // 两面都要，因为一个**从不排除任何东西**的时间筛选，自己就能让「包含它」那条断言通过。
        assertEquals(1, rows("?userId=" + who + "&since=2000-01-01", ADMIN).size(),
                "a period starting before the row must include it");
        assertTrue(rows("?userId=" + who + "&since=2099-01-01", ADMIN).isEmpty(),
                "a period starting after the row must exclude it");
    }

    @Test
    void anOutcomeFilterNarrowsToTheRowsThatWentWrong() {
        String who = "outcome-" + UUID.randomUUID();
        audit.append("tool_call", who, "the call went as asked");
        audit.append("tool_call", who, "the call did not", true);

        List<?> errors = rows("?userId=" + who + "&isError=true", ADMIN);

        assertEquals(1, errors.size(), "only the row recorded as an error may come back");
        assertEquals("the call did not", ((Map<?, ?>) errors.get(0)).get("detail"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) errors.get(0)).get("isError"));
    }

    @Test
    void aCallerWhoIsNotAnAdministratorIsRefusedTheRows() {
        ResponseEntity<String> response = get("/audit/logs", CALLER);

        assertEquals(403, response.getStatusCode().value(),
                "the answer carries the rows themselves, so it is not readable by every caller");
    }

    @Test
    void aFilterThisDeploymentCannotApplyIsRefusedRatherThanIgnored() {
        // The reference offers all three; this runtime records none of what they filter on. Accepting
        // one and not applying it would return a list that looks filtered and is not, and nothing
        // downstream could tell the difference.
        //
        // 参照实现三个都提供，而本运行时**不记它们筛的任何东西**。接受一个却**不施加**它，回出来的是一份
        // **看着像筛过、其实没有**的列表，而下游分不出区别。
        for (String unsupported : List.of("agentId=someone", "orgId=1", "denied=true")) {
            ResponseEntity<String> response = get("/audit/logs?" + unsupported, ADMIN);
            assertEquals(400, response.getStatusCode().value(),
                    "`" + unsupported + "` cannot be honoured here, so it must be refused: "
                            + response);
        }
    }
}
