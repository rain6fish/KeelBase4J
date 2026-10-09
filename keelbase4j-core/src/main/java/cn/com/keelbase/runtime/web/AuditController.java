// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AI audit chain, read back: does it hold, and which rows are on it.
 *
 * <p><b>Administrators only, and that is a change.</b> The verify route used to answer any
 * authenticated caller, which was survivable while it returned a verdict and nothing else. It now
 * also returns the chain — the rows it walked, with who acted and what they did — and a chain
 * readable by everyone is not an audit trail, it is a roster. The object's own implementation gates
 * the same route the same way, and the corpus replays it as an administrator, so this is the
 * deployment catching up to what the surface was always meant to be rather than a policy invented
 * here. The same reasoning settles the second route below, which returns rows by design.
 *
 * <p>问的是 AI 审计链的读回：链站不站得住，以及链上是哪些行。
 *
 * <p><b>只给管理员，而这是一处改动。</b>校验那条路由过去**任何带 token 的调用方**都能问 —— 在它只回一个
 * **结论**时那样尚可。它现在**还把链也回出来**：它走过的那些行、谁动的手、做了什么 —— 而一条**人人可读**的链
 * 不是审计轨，是**花名册**。这个对象**自己的实现**用同样的方式守着同一条路由，语料也以**管理员**身份重放它，
 * 所以这里是一个部署**追平这条面本来就该有的样子**，而不是在本地发明一条策略。同一条理由也定了下面那条
 * **本来就按设计回行**的路由。
 */
@RestController
public class AuditController {

    /**
     * The reference's own audit query answers 50 rows by default and caps a page at 200.
     *
     * 参照实现自己那条审计查询默认答 50 行、一页封顶 200。
     */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /**
     * How far into the chain a caller may start. The rows before {@code offset} are fetched and
     * dropped (see {@code AuditService.rows}), so this is what keeps that fetch bounded — past it,
     * the honest answer is a refusal rather than a scan.
     *
     * 调用方能从链上多深开始。{@code offset} 之前的行是**取回来再丢掉**的（见 {@code AuditService.rows}），
     * 故这个数正是那次取数有界的原因 —— 超过它，诚实的答案是**拒绝**，不是全表扫。
     */
    private static final int MAX_OFFSET = 1000;

    private final AuditService audit;
    private final CurrentPrincipal principals;

    public AuditController(AuditService audit, CurrentPrincipal principals) {
        this.audit = audit;
        this.principals = principals;
    }

    @GetMapping("/audit/verify")
    public AuditService.Verification verify() {
        requireAdministrator();
        return audit.verify();
    }

    /**
     * The rows on the chain, filtered by caller and by period.
     *
     * <p>The reference's query is the shape this answers: a caller, a period, an outcome, and a
     * window. Two things it offers are deliberately not answered here — agent and organisation
     * identity, and the authorization-verdict view — because this runtime records none of those on
     * an audit row. They are <em>refused</em> rather than ignored: a filter that is accepted and not
     * applied returns a list that looks filtered and is not, and nothing downstream can tell the
     * difference, which is worse than an error that says so.
     *
     * <p>链上的那些行，按调用者和一段时间筛选。
     *
     * <p>它答的就是参照实现那条查询的形状：一个调用者、一段时间、一个结果、一个窗口。参照实现另有**两样**这里
     * **刻意不答** —— agent 与组织身份、以及授权结论那个视图 —— 因为本运行时在审计行上**一个都不记**。它们是
     * **被拒绝**的、不是被忽略的：一个**被接受却没被施加**的筛选回出来的是一份**看着像筛过、其实没有**的列表，
     * 而下游分不出这个区别 —— 那比一个**把话说出来**的错误更糟。
     */
    @GetMapping("/audit/logs")
    public List<Map<String, Object>> logs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String isError,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String denied,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireAdministrator();

        if (agentId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "this deployment records no agent identity on an audit row, so it cannot filter "
                            + "by agentId");
        }
        if (orgId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "this deployment records no organisation on an audit row, so it cannot filter "
                            + "by orgId");
        }
        if (denied != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "this deployment records no authorisation verdict on an audit row, so it cannot "
                            + "answer the denied view");
        }

        return audit.rows(userId,
                since == null ? null : instant(since),
                outcome(isError),
                Math.min(Math.max(limit, 1), MAX_LIMIT),
                Math.min(Math.max(offset, 0), MAX_OFFSET));
    }

    /**
     * The chain is a roster to everyone who may act on it, and a roster is not readable by all.
     *
     * 这条链对**每一个能动它的人**都是一份花名册，而花名册**不是人人都能读的**。
     */
    private void requireAdministrator() {
        Principal caller = principals.current();
        if (!caller.isManager()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "the audit chain is readable by an administrator");
        }
    }

    /**
     * The reference reads this filter as the two words, so anything else is a request this surface
     * cannot answer rather than one that quietly means "false".
     *
     * 参照实现把这个筛选读成那两种取值，故其它任何输入都是**这条面答不了**的请求，而不是一个**悄悄等于 false**
     * 的请求。
     */
    private static Boolean outcome(String isError) {
        if (isError == null) {
            return null;
        }
        if (!"true".equals(isError) && !"false".equals(isError)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "isError is `true` or `false`");
        }
        return Boolean.valueOf(isError);
    }

    /**
     * The reference parses this with {@code new Date(...)}, which takes a date and a date-time alike;
     * a deployment off by a day is worse than one that says it could not read the input.
     *
     * 参照实现用 {@code new Date(...)} 解析它，日期与日期时间都收；一个**差了一天**的部署比一个**说明自己
     * 读不懂输入**的部署更糟。
     */
    private static Instant instant(String since) {
        try {
            return Instant.parse(since);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(since).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADateEither) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "since must be an ISO-8601 instant or date, such as 2026-10-09 or "
                                + "2026-10-09T00:00:00Z");
            }
        }
    }
}
