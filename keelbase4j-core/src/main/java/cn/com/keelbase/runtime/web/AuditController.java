// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
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
 * <p><b>Administrators or an audit reader — never every caller.</b> The verify route used to answer
 * any authenticated caller, which was survivable while it returned a verdict and nothing else. It now
 * also returns the chain — the rows it walked, with who acted and what they did — and a chain
 * readable by everyone is not an audit trail, it is a roster. The object's own implementation gates
 * the same route the same way, and the corpus replays it as an administrator, so this is the
 * deployment catching up to what the surface was always meant to be rather than a policy invented
 * here. The same reasoning settles the second route below, which returns rows by design.
 *
 * <p>Since JV-63 the gate is the capability seam rather than an inline {@code isManager()}: read-only
 * audit readers are a deployment's decision, expressed as a rule, not a change to this class.
 *
 * <p>问的是 AI 审计链的读回：链站不站得住，以及链上是哪些行。
 *
 * <p><b>给管理员或审计读者 —— 绝不是每个调用方。</b>校验那条路由过去**任何带 token 的调用方**都能问 ——
 * 在它只回一个**结论**时那样尚可。它现在**还把链也回出来**：它走过的那些行、谁动的手、做了什么 —— 而一条
 * **人人可读**的链不是审计轨，是**花名册**。这个对象**自己的实现**用同样的方式守着同一条路由，语料也以
 * **管理员**身份重放它，所以这里是一个部署**追平这条面本来就该有的样子**，而不是在本地发明一条策略。同一条
 * 理由也定了下面那条**本来就按设计回行**的路由。
 *
 * <p>JV-63 起，这道闸是**能力缝**、不是内联的 `isManager()`：只读审计读者是部署方用一条规则表达的**决定**，
 * 不再是改这个类。
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
    private final OwnershipGuard guard;

    public AuditController(AuditService audit, CurrentPrincipal principals, OwnershipGuard guard) {
        this.audit = audit;
        this.principals = principals;
        this.guard = guard;
    }

    @GetMapping("/audit/verify")
    public AuditService.Verification verify() {
        requireAuditRead();
        return audit.verify();
    }

    /**
     * The rows on the chain, filtered by caller, by a closed period, and by business object.
     *
     * <p>The reference's query is the shape this answers: a caller, a period, an outcome, a window —
     * and now the period's end (`until`) and an object (`objectType` + `objectId`), which is what an
     * auditor's question needs ("everything that happened to customer #9005, last week"). The object
     * is resolved through the ledger: the effects carrying that pair give their execution ids, and
     * the rows of exactly those executions are the answer. Two things the reference offers are still
     * deliberately not answered here — agent and organisation identity, and the authorization-verdict
     * view — because this runtime records none of those on an audit row. They are <em>refused</em>
     * rather than ignored: a filter that is accepted and not applied returns a list that looks
     * filtered and is not, and nothing downstream can tell the difference, which is worse than an
     * error that says so.
     *
     * <p>链上的那些行，按调用者、按闭合的区间、按业务对象筛选。
     *
     * <p>它答的就是参照实现那条查询的形状：一个调用者、一段时间、一个结果、一个窗口 —— 现在还加上区间的
     * **终点**（`until`）与**对象**（`objectType` + `objectId`），那正是审计员那个问题的两个要件（「客户
     * #9005 上周发生过什么」）。对象经台账解析：带该对的副作用给出它们的执行 id，**恰好**那些执行的行就是
     * 答案。参照实现另有的两样仍**刻意不答** —— agent 与组织身份、以及授权结论那个视图 —— 因为本运行时在
     * 审计行上**一个都不记**。它们是**被拒绝**的、不是被忽略的：一个**被接受却没被施加**的筛选回出来的是一份
     * **看着像筛过、其实没有**的列表，而下游分不出这个区别 —— 那比一个**把话说出来**的错误更糟。
     */
    @GetMapping("/audit/logs")
    public List<Map<String, Object>> logs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String isError,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String denied,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireAuditRead();

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
        // Half an object filter is a 400, not a silently wider read: an input error that widens the
        // answer is the failure this guard exists to stop.
        // 半个对象过滤是 400、不是悄悄放宽的读：一个把答案放大的输入错误，正是这条护栏要拦的。
        if ((objectType == null) != (objectId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "objectType and objectId must be given together");
        }

        // A deployment without an effect ledger cannot answer this filter. Saying 503 is the honest
        // answer; an empty page would read as "nothing happened to that object".
        // 没有台账的部署答不了这个过滤。**503** 才是如实的答复；一个空页会被读成「那条对象什么都没发生」。
        if (objectType != null && !audit.hasEffectLedger()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "this deployment has no effect ledger, so it cannot search by business object");
        }
        List<Long> objectRows = objectType == null
                ? null
                : audit.objectRowIds(objectType, objectId);

        return audit.rows(userId,
                since == null ? null : instant(since),
                until == null ? null : instant(until),
                outcome(isError),
                Math.min(Math.max(limit, 1), MAX_LIMIT),
                Math.min(Math.max(offset, 0), MAX_OFFSET),
                objectRows);
    }

    /**
     * The chain is a roster, and a roster is read by whoever a deployment grants the capability to.
     *
     * <p>It used to be a hardcoded {@code isManager()} check, which meant a deployment could not name
     * a read-only audit reader without editing this class (JV-63). The rule source decides now: the
     * tier-A default still grants {@code read} on this subject to administrators only, and a tier-B
     * source may grant it to an identity whose every grant is {@code read} anyway.
     *
     * 这条链是一份花名册，而花名册由**部署方授予了这项能力的人**来读。
     *
     * <p>它从前是一处写死的 `isManager()` 判断，意思是部署方若不改这个类，就点不出一个只读的审计读者
     * （JV-63）。现在由规则源决定：档 A 默认仍只把该 subject 的 `read` 授予管理员，而档 B 可以把它授予
     * 一个**全部授予都只有 `read`** 的身份。
     */
    private void requireAuditRead() {
        guard.requireAction(principals.current(), GovernanceReadSubjects.AUDIT_LOG,
                PermissionAuthorizer.ACTION_READ);
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
    static Instant instant(String since) {
        return instant("since", since);
    }

    /** The same parser, naming the field it is refusing — the coverage endpoint reads two boundaries. */
    /** 同一个解析器，并点名它拒绝的是哪个字段 —— 覆盖声明端点读两个边界。 */
    static Instant instant(String field, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADateEither) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " must be an ISO-8601 instant or date, such as 2026-10-09 or "
                                + "2026-10-09T00:00:00Z");
            }
        }
    }
}
