// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspect and revoke AI write side effects (the recovery half of the trust boundary).
 *
 * <p>Both endpoints act as the authenticated caller: the list is narrowed to what that caller may
 * see, and a revoke is checked against them, never against a name the request carries.
 *
 * <p>The list answers the shape the console reads — {@code {total, page, limit, items}} with the
 * caller's {@code page} / {@code limit}, the page size capped as the reference caps it. Serving a
 * bare array meant a runtime-neutral console could read this endpoint on one runtime and not the
 * other, which is the whole thing the unified frontend is supposed to avoid.
 *
 * <p><b>What the items deliberately do not carry.</b> The console's model has fields this runtime has
 * no concept of — before/after snapshots, compensation groups, parent effects, and the change summary
 * derived from those snapshots. They are omitted rather than invented, and a console column that reads
 * one of them is empty here: that is the honest state rather than a filled-in guess.
 *
 * <p><b>{@code conversationId} is carried, and the reader's own scope is what makes that safe.</b> The
 * frozen object declares it, the runtime has always stored it, and {@code GET
 * /ai/tool-effects?conversationId=…} narrows a page to one turn. A caller asking for somebody else's
 * conversation gets an empty answer, never their rows. The field used to be sent as null on the
 * reasoning that this runtime has no conversations — which stopped being true when the runtime grew a
 * conversation store, and the reader that does have one (the trace) has read the column all along.
 *
 * <p>本列表**刻意不装**的：控制台那个模型有、而本运行时没有概念的字段 —— 前后快照、补偿组、父副作用，以及由那些
 * 快照推出的变更摘要。它们**缺席而不是编出来**，读它们的控制台列在这里是空的：那是诚实的状态，不是一个填进去的猜测。
 *
 * <p><b>`conversationId` 是**带着**的，而让这件事安全的正是读者自己的范围。</b>冻结对象声明了它、本运行时**一直**
 * 在存它，而 `GET /ai/tool-effects?conversationId=…` 能把一页收窄到**一轮**。一个点名**别人**会话的调用方拿到的是
 * **空**、从不是他们的行。这个字段从前按「本运行时没有会话」的理由送 null —— 那句话在运行时长出会话存储时就不再成立，
 * 而**真的**有会话的那个读者（trace）一直在读这一列。
 */
@RestController
public class EffectController {

    /** The reference caps a page at 100; a caller cannot ask for the whole table by asking nicely. */
    private static final int MAX_PAGE_SIZE = 100;

    /** The runtime's only local entity, and so its only revocable target. */
    private static final String FOLLOW_UP = "follow_up";

    private final CurrentPrincipal principals;
    private final SideEffectService sideEffects;
    private final SideEffectRepository repository;
    private final FollowUpRepository followUps;
    private final PermissionAuthorizer authorizer;

    public EffectController(CurrentPrincipal principals, SideEffectService sideEffects,
                            SideEffectRepository repository, FollowUpRepository followUps,
                            PermissionAuthorizer authorizer) {
        this.principals = principals;
        this.sideEffects = sideEffects;
        this.repository = repository;
        this.followUps = followUps;
        this.authorizer = authorizer;
    }

    @GetMapping("/ai/tool-effects")
    public Map<String, Object> list(@RequestParam(required = false) String userId,
                                    @RequestParam(required = false) String conversationId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        Principal principal = principals.current();
        int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, size);

        // A blank `conversationId` means "no filter asked for", not "the effects of the conversation
        // whose id is the empty string" — a call made outside a conversation stores null in that
        // column, so the two would otherwise mean different things behind one query parameter.
        // 空的 `conversationId` 是「没有要求筛选」，不是「id 为空字符串的那条会话的副作用」——**会话之外**的调用
        // 在该列存的是 null，两者否则会在同一个查询参数后面表示不同的东西。
        String conversation = conversationId == null || conversationId.isBlank() ? null : conversationId;

        // A manager sees the whole ledger; so does an audit reader — an identity whose every grant is
        // `read` and that was granted `read` on this subject (JV-63 B5). **Decided 2026-10-20
        // (candidate ②): the read-only reader's rows omit `userId` and `conversationId`.** Nothing
        // else is narrowed — the snapshots are required by the frozen `traceItem`, so narrowing them
        // is a contract change (v6), which this decision did not take. The administrator's and an
        // ordinary caller's own view keep both keys exactly as before. This is a **separate question**
        // from the confirmation queue's `arguments`, which that reader still receives in full and
        // which remains undecided.
        //
        // 管理员看整本账；审计读者也看整本账 —— 那个身份的每一条授予都只有 `read`，且在该 subject 上被授予了
        // `read`（JV-63 B5）。**2026-10-20 已裁（候选 ②）：只读读者的每一行去掉 `userId` 与
        // `conversationId`。** 其余一律不收窄 —— 快照是冻结 `traceItem` 的必需字段，动它等于契约升 v6，而本次
        // 裁定没有走那条路。管理员与普通调用方**本人**的视图两项照旧都在。这与确认队列的 `arguments` 是
        // **两件事**：那个面该读者今天仍读到全部实参，而那条**仍未裁**。
        // The grant must cover **all** rows, not merely allow the action: a tier-B rule may declare
        // `read` with an owner field, and treating that as global would return every caller's rows.
        // 授予必须覆盖**全部行**，而不只是允许该动作：档 B 规则可以带 owner 字段声明 `read`，
        // 把它当作全局读就会返回所有人的行。
        PermissionCapabilityList.Resource effectRead =
                authorizer.capabilityFor(principal, GovernanceReadSubjects.TOOL_EFFECT);
        boolean manager = principal.isManager();
        boolean globalRead = manager
                || (effectRead != null
                    && PermissionCapabilityList.SCOPE_ALL.equals(effectRead.scope())
                    && effectRead.actions().contains(PermissionAuthorizer.ACTION_READ));
        // One criterion decides whether the caller linkage is projected: the manager and every caller
        // reading their own rows keep it, the read-only reader who reaches every row through the seam
        // does not (JV-63 B5 ②). It is the same `globalRead` decision, not a second one.
        //
        // 调用方关联投不投影，只由**同一个判据**决定：管理员与**读自己那些行**的调用方都带它；只读读者经缝读到
        // 全部行、则不带（JV-63 B5 ②）。用的是同一个 `globalRead` 判据，不是又判一次。
        boolean callerLinkage = manager || !globalRead;

        Page<SideEffect> found;
        if (!globalRead) {
            // A caller without the global read sees their own effects whatever they ask for — the
            // filter is not theirs to lift — and naming somebody else's conversation answers with
            // nothing, which is how a conversation that is not theirs answers everywhere else too.
            // 没有全局读权的调用方无论请求什么看到的都是自己那些行 —— 那个筛选不是他们能抬掉的；而点名**别人**的
            // 会话，答案就是**空** —— 与「不是他的会话」在别处一律如此作答相符。
            found = conversation == null
                    ? repository.findByUserIdOrderByIdDesc(principal.userId(), pageable)
                    : repository.findByUserIdAndConversationIdOrderByIdDesc(
                            principal.userId(), conversation, pageable);
        } else if (userId != null && conversation != null) {
            found = repository.findByUserIdAndConversationIdOrderByIdDesc(userId, conversation, pageable);
        } else if (conversation != null) {
            found = repository.findByConversationIdOrderByIdDesc(conversation, pageable);
        } else if (userId != null) {
            found = repository.findByUserIdOrderByIdDesc(userId, pageable);
        } else {
            found = repository.findAll(pageable);
        }

        List<Map<String, Object>> items = found.getContent().stream()
                .map(effect -> view(effect, callerLinkage))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", found.getTotalElements());
        body.put("page", page);
        body.put("limit", size);
        body.put("items", items);
        return body;
    }

    @DeleteMapping("/ai/tool-effects/{id}")
    public Map<String, Object> revoke(@PathVariable Long id) {
        Principal principal = principals.current();
        SideEffect effect = sideEffects.revoke(id, principal);
        // `revoked` is the frozen `revokeResult`'s required field, and `revokeStatus` says the same
        // thing in the contract's own vocabulary (the console reads that one). Both, because a
        // consumer holding the frozen object should not have to derive a required field from another.
        return Map.of(
                "effectId", effect.getId(),
                "resultType", effect.getResultType(),
                "revokeClass", effect.getRevokeClass(),
                "revokeStatus", effect.getRevokeStatus(),
                "revoked", "revoked".equals(effect.getRevokeStatus()));
    }

    /** A {@link LinkedHashMap}, not {@code Map.of}: several of these fields are legitimately null. */
    private Map<String, Object> view(SideEffect e, boolean callerLinkage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("toolName", e.getToolName());
        // Read, not pinned to null. The frozen `effect` object declares this field as `string|null`,
        // and this runtime has always stored it; what was missing was a reader. `ConversationController`
        // has read the column this way for its trace steps all along, so the list was the one place
        // that claimed the runtime had no conversations.
        //
        // **Two vocabularies live in this column** and that is worth knowing before consuming it: a
        // call on the runtime's own chat path carries a conversation id, while a call made by a host
        // through its own agent session carries *that session's* id. Both are strings; the trace
        // endpoint answers 404 for the second kind, because it has no conversation of that id.
        //
        // **读真值，不再钉在 null 上。** 冻结对象 `effect` 把这个字段声明为 `string|null`，而本运行时**一直**在存它；
        // 缺的只是一个**读它的人**。`ConversationController` 为它的 trace 步骤读这一列读到现在，故那条列表是**唯一**
        // 声称「本运行时没有会话」的地方。
        //
        // **这一列里住着两套词表**，用它之前值得知道：运行时自己那条 chat 路上的调用带的是**会话 id**，而一个宿主经
        // 自己的 agent 会话发出的调用带的是**那条会话的** id。两者都是字符串；trace 端点对第二种答 404，因为它没有
        // 那个 id 的会话。
        // The caller linkage keys are the ones the read-only reader does not receive (JV-63 B5 ②);
        // the manager and a caller's own list keep both. See the `list()` comment for the decision.
        //
        // 调用方关联这两键是只读读者**不收**的（JV-63 B5 ②）；管理员与本人清单两者都在。裁定见 `list()` 的注释。
        if (callerLinkage) {
            m.put("conversationId", e.getConversationId());
        }
        // Who the write was for. The column has been non-null since it was added; what was missing was
        // the wire field that lets a console answer "who", which is why the contract now declares it
        // (optional there, so a v4-shaped payload stays valid). A manager and a caller reading their
        // own rows always receive it; the read-only global reader does not (JV-63 B5 ②).
        //
        // 这次写是**为谁**写的。这一列自加进来起就非空；缺的是让控制台答得出「谁」的那个 wire
        // 字段，故契约现在声明了它（在契约里是**可选**的，好让 v4 形态的载荷继续合法）。管理员与
        // **读自己那些行**的调用方总是收到它；只读的全局读者**不**收（JV-63 B5 ②）。
        if (callerLinkage) {
            m.put("userId", e.getUserId());
        }
        m.put("resultType", e.getResultType());
        m.put("resultId", e.getResultId());
        m.put("argsHash", e.getArgsHash());
        m.put("createdAt", e.getCreatedAt().toString());

        Optional<FollowUp> target = target(e);
        m.put("targetExists", target.isPresent());
        m.put("targetSoftDeleted", target.map(t -> t.getDeletedAt() != null).orElse(false));
        // The only human-facing label a follow-up has is its note; the console shows it as the
        // target's title.
        m.put("targetTitle", target.map(FollowUp::getNote).orElse(null));

        m.put("revokeClass", e.getRevokeClass());
        // One stored column feeds two contract fields, and the contract gives the two different
        // vocabularies — `status` says what happened to the effect (executed / revoked /
        // revoking_external / revoke_failed), `revokeStatus` says where the revoke is (revoked /
        // compensating / revoke_failed, and null while none was ever attempted). The column's three
        // values map onto them without a guess: `executed` means no revoke has been tried, so the
        // revoke field is null; `revoked` means both; and `compensating` is a state of the *revoke*,
        // so the effect's own status stays `executed` until the compensation finishes.
        //
        // 一条存储列喂着契约的**两个**字段，而两者词表不同 —— `status` 说 effect 怎么了
        // （executed / revoked / revoking_external / revoke_failed），`revokeStatus` 说撤销走到哪
        // （revoked / compensating / revoke_failed，**从未撤销过则是 null**）。列上的三个取值不需要猜就能
        // 对上：`executed` ＝ 还没试过撤销 ⇒ 撤销字段为 null；`revoked` ＝ 两者都是；而 `compensating`
        // 是**撤销**的状态，所以 effect 自己的 status 在补偿结束前**仍是 `executed`**。
        String stored = e.getRevokeStatus();
        m.put("status", "revoked".equals(stored) ? "revoked" : "executed");
        m.put("revokeStatus", "executed".equals(stored) ? null : stored);

        // The frozen `traceItem`'s remaining three, answered with what this runtime has:
        //
        //   `afterSnapshot` — what the tool produced, stored when the write ran, because the row as it
        //   stands today is not the row the decision produced.
        //   `beforeSnapshot` — the row as it was before the write changed it, when the tool supplied
        //   one, and null otherwise — a create has no before, and that is the fact rather than a gap.
        //   `compensationGroup` / `parentEffectId` — declared nullable, and null: this runtime has
        //   neither a compensation group nor a parent/child relation between effects, and inventing
        //   one would be a claim with nothing behind it.
        //
        // 冻结 `traceItem` 余下的三样，按本运行时**有的**答：
        //
        //   `afterSnapshot` —— 工具产出了什么，**在写跑成时存下**：今天这一行的样子，不是这次决策产出的样子。
        //   `beforeSnapshot` —— 这一行被这次写改**之前**的样子（工具交了才有的），否则 null —— **新建**没有「之前」，
        //   而那是**事实**、不是缺口。
        //   `compensationGroup` / `parentEffectId` —— 声明可空、而这里就是 null：本运行时既没有补偿组，
        //   也没有 effect 之间的父子关系，编一个出来只是**没有任何东西支撑的主张**。
        m.put("beforeSnapshot", e.getBeforeSnapshot());
        m.put("afterSnapshot", e.getAfterSnapshot());
        m.put("compensationGroup", null);
        m.put("parentEffectId", null);

        // What the console renders the revoke button on, decided here rather than re-derived there.
        m.put("revocable",
                !"none".equals(e.getRevokeClass()) && "executed".equals(e.getRevokeStatus()));
        return m;
    }

    private Optional<FollowUp> target(SideEffect e) {
        return FOLLOW_UP.equals(e.getResultType()) ? followUps.findById(e.getResultId()) : Optional.empty();
    }
}
