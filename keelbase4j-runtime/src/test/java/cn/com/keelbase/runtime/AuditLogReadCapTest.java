// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * The audit read face is bounded by a setting, and the bound holds on both object roads.
 *
 * <p>The cap is asserted through an application configured to small values, which is the only way to
 * prove it is a setting rather than a constant: a page defaulting to three rows, capped at five, with
 * two rows of paging depth. Every over-asked request below is <em>clamped, not refused</em> — that is
 * the semantics the runtime documents — so each assertion also says what an oversized request is
 * served with. The object scan is fed exactly the span its page can reach, so the same small cap
 * bounds the ledger road, the sentence road, and the second page of either.
 *
 * <p>审计读面由一个设置界定，而两条对象路都受这个界约束。
 *
 * <p>上限是**通过一个配成小值的应用**断言的 —— 这是唯一能证明它是**设置**而不是常量的办法：默认三行、
 * 封顶五行、翻页深度两行。下面每一个超额请求都是**被钳制、不是被拒绝** —— 那正是运行时写明的语义 ——
 * 故每条断言也一并说明超额请求得到的是什么。对象扫描拿到的正好是它那一页够得到的跨度，故同一个小的上限
 * 同时界定台账路、句末路，以及任一条路的第二页。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "keelbase.audit.logs.default-limit=3",
        "keelbase.audit.logs.max-limit=5",
        "keelbase.audit.logs.max-offset=2" })
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class AuditLogReadCapTest {

    private static final String ADMIN = "carol";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Autowired
    SideEffectRepository sideEffects;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(ADMIN, delegationSecret));
        return rest.exchange(URI.create(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private List<?> rows(String query) {
        ResponseEntity<String> response = get("/audit/logs" + query);
        assertEquals(200, response.getStatusCode().value(), "the read must be served: " + response);
        return (List<?>) Envelopes.data(Vectors.map(Json.parse(response.getBody())));
    }

    private static List<String> details(List<?> rows) {
        List<String> details = new ArrayList<>();
        for (Object row : rows) {
            details.add((String) ((Map<?, ?>) row).get("detail"));
        }
        return details;
    }

    private String newCaller() {
        return "cap-" + UUID.randomUUID();
    }

    private void appendRows(String who, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            audit.append("tool_call", who, "r" + i, false, "executed", "create_followup");
        }
    }

    /**
     * The default, the cap and the over-asked page all come from the setting.
     *
     * <p>Three assertions in one test on purpose: they are the same question — which of these three
     * numbers did the deployment's setting produce — and splitting them would let one pass while the
     * page silently ignored another.
     *
     * 默认值、上限与超额页都来自这个设置。
     *
     * <p>三条断言故意放在同一个测试里：它们是同一个问题 —— 这三个数字里哪些来自部署设置 —— 拆开会让
     * 一条通过、而这一页悄悄忽略另一条。
     */
    @Test
    void thePageSizeComesFromTheSettingAndIsClampedToTheCap() {
        String who = newCaller();
        appendRows(who, 7);

        List<?> byDefault = rows("?userId=" + who);
        assertEquals(List.of("r7", "r6", "r5"), details(byDefault),
                "without a limit the page is the configured default (3), newest first");

        assertEquals(5, rows("?userId=" + who + "&limit=5").size(),
                "the configured maximum is served in full");

        assertEquals(5, rows("?userId=" + who + "&limit=6").size(),
                "one row over the cap is served at the cap, not refused");

        assertEquals(5, rows("?userId=" + who + "&limit=100000").size(),
                "an absurdly large page is still served at the cap");
        assertEquals(5, rows("?userId=" + who + "&limit=" + Integer.MAX_VALUE).size(),
                "the cap survives the largest number a client can send");
    }

    /**
     * Values below one are clamped into the range, not refused and not turned into "no rows".
     *
     * <p>A zero-row page would be indistinguishable from "the chain has nothing", which is the one
     * answer an over-narrow request must not produce by accident.
     *
     * 小于一的取值被钳进区间，既不被拒绝、也不变成「零行」。
     *
     * <p>零行的一页与「链上没有东西」无法区分，而那正是一个过窄请求绝不能**碰巧**给出的答案。
     */
    @Test
    void aZeroOrNegativeLimitAnswersTheSmallestPage() {
        String who = newCaller();
        audit.append("tool_call", who, "older", false, "executed", "create_followup");
        audit.append("tool_call", who, "newest", false, "executed", "create_followup");

        assertEquals(List.of("newest"), details(rows("?userId=" + who + "&limit=0")),
                "a zero limit answers one row, the newest");
        assertEquals(List.of("newest"), details(rows("?userId=" + who + "&limit=-9")),
                "a negative limit answers one row, the newest");
    }

    /**
     * The offset has the same clamped-in semantics: negative starts at the newest row, past the cap
     * starts at the cap, and a page past the end is empty rather than an error.
     *
     * 起点同属「钳进去」的语义：负的从最新一行开始，超过上限的从上限开始，越过末尾的一页是空的、不是错误。
     */
    @Test
    void anOffsetOutsideTheRangeIsServedFromTheNearestEdge() {
        String who = newCaller();
        appendRows(who, 7);

        assertEquals(List.of("r7", "r6", "r5"), details(rows("?userId=" + who + "&offset=-4&limit=3")),
                "a negative offset must start at the newest row, exactly like zero");
        assertEquals(details(rows("?userId=" + who + "&limit=3&offset=0")),
                details(rows("?userId=" + who + "&offset=-4&limit=3")),
                "and the two pages must be the same page");

        // The configured maximum offset is 2, so 9999 is served from row 2 of the newest seven.
        assertEquals(List.of("r5", "r4", "r3", "r2", "r1"),
                details(rows("?userId=" + who + "&offset=9999&limit=5")),
                "an offset past the cap starts at the cap, so the page is short but still a page");

        String oneRow = newCaller();
        audit.append("tool_call", oneRow, "only", false, "executed", "create_followup");
        assertTrue(rows("?userId=" + oneRow + "&offset=9999&limit=5").isEmpty(),
                "an offset past the rows that exist is an empty page, served as 200");
    }

    /**
     * Both object roads are bounded by the same window, and the window is the page's own span.
     *
     * <p>The second page is the assertion that matters: with seven object rows, a cap of five and a
     * depth of two, {@code offset=2&limit=5} must answer five rows — which is only possible if the
     * scan was given seven (offset + limit), not five. A scan capped at the page size alone would
     * answer three here and look like "the object has fewer rows than it does".
     *
     * <p>Both roads also stay inside the request's scope before the window cuts: seven rows for
     * another caller are appended afterwards, and they must not crowd out the caller's own page.
     *
     * 两条对象路由同一个窗口界定，而窗口就是这一页自己的跨度。
     *
     * <p>第二页才是要紧的那条断言：对象有七行、上限五、深度二时，{@code offset=2&limit=5} 必须答五行 ——
     * 这只有在扫描拿到的是七（offset + limit）、而不是五时才可能。只按页大小封顶的扫描这里会答三行、看着
     * 像「这条对象的行比实际少」。
     *
     * <p>两条路在窗口切之前也都留在本次请求的范围内：随后写下的另一个调用方的七行，不得挤掉本人的这一页。
     */
    @Test
    void theObjectScanIsBoundedByThePagesOwnSpanOnBothRoads() {
        String kind = "cap-kind-" + UUID.randomUUID();
        String key = "13" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L);
        String mine = newCaller();
        String other = newCaller();
        List<String> myExecutions = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String execution = "exec-" + UUID.randomUUID();
            myExecutions.add(execution);
            audit.append("tool_call", mine, "ledger " + i, false, "executed", "create_followup",
                    null, null, execution);
            sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), mine, "create_followup", kind,
                    key, "local_compensate", "hash", null, null, null, execution));
        }
        // Newer rows for another caller on the same object: outside this request's scope, so they
        // must be cut by the query — not by the window, which would starve the caller's page.
        // 同一个对象上更新、但属于另一个调用方的行：在本次请求的范围之外，故必须被**查询**切掉 ——
        // 而不是被窗口切掉，后者会让本人的这一页饿死。
        for (int i = 1; i <= 7; i++) {
            String execution = "exec-" + UUID.randomUUID();
            audit.append("tool_call", other, "foreign " + i, false, "executed", "create_followup",
                    null, null, execution);
            sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), other, "create_followup",
                    kind, key, "local_compensate", "hash", null, null, null, execution));
        }

        String query = "?objectType=" + kind + "&objectId=" + key + "&userId=" + mine;
        List<?> byDefault = rows(query);
        assertEquals(3, byDefault.size(), "the ledger road answers the configured default page");
        assertEquals(5, rows(query + "&limit=100000").size(),
                "the ledger road is capped at the configured maximum, not at the chain's size");
        List<?> secondPage = rows(query + "&offset=2&limit=5");
        assertEquals(5, secondPage.size(),
                "the ledger scan must span offset + limit, or the second page comes back short");
        assertEquals(List.of("ledger 5", "ledger 4", "ledger 3", "ledger 2", "ledger 1"),
                details(secondPage), "newest first, and only this caller's rows");
        Set<Object> selected = secondPage.stream()
                .map(row -> ((Map<?, ?>) row).get("executionId"))
                .collect(Collectors.toSet());
        assertTrue(myExecutions.containsAll((java.util.Collection<?>) selected),
                "no row of another execution may appear under this object and caller");

        String word = "sentence-kind-" + UUID.randomUUID();
        String sentenceKey = "14" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L);
        for (int i = 1; i <= 7; i++) {
            audit.append("analyze", mine,
                    "probe " + i + " · object " + word + " #" + sentenceKey);
        }
        String sentenceQuery = "?objectType=" + word + "&objectId=" + sentenceKey;
        assertEquals(3, rows(sentenceQuery).size(),
                "the sentence road answers the configured default page");
        assertEquals(5, rows(sentenceQuery + "&limit=100000").size(),
                "the sentence road is capped at the same configured maximum");
        assertEquals(List.of("probe 5 · object " + word + " #" + sentenceKey,
                        "probe 4 · object " + word + " #" + sentenceKey,
                        "probe 3 · object " + word + " #" + sentenceKey,
                        "probe 2 · object " + word + " #" + sentenceKey,
                        "probe 1 · object " + word + " #" + sentenceKey),
                details(rows(sentenceQuery + "&offset=2&limit=5")),
                "the sentence scan must span offset + limit too, on the same terms as the ledger");
    }
}
