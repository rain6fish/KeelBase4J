// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The governance policy: what this deployment has declared about its tools, and where a console
 * changes it — administrators only.
 *
 * <p>This is the console's policy page, and it answers the frozen {@code governance-policy} object
 * with nothing added: the per-tool overrides and the audit granularity are the whole of it, plus the
 * two keys the object carries about the policy itself ({@code updatedAt}, {@code revision}) which this
 * runtime fills in rather than accepts. What a GET returns is therefore also a valid PUT body — a
 * console that reads a policy and writes it back is not refused for returning what it was handed.
 *
 * <p><b>The policy is enforced, so it is not a page of switches wired to nothing.</b> A tool it
 * disables, a gate mode it changes and a role list it narrows all land on the same gate the engine
 * runs every call through ({@code GovernanceService.decide}); a written policy changes what happens
 * next, not only what a page shows. What the store will not do is accept a setting it cannot apply —
 * see {@link GovernancePolicyStore} — so a submission that names a tool this deployment does not
 * register, a mode outside the frozen three, or a role outside this deployment's vocabulary comes back
 * as a {@code 400} with the reason rather than being stored and quietly ignored.
 *
 * <p>**治理策略**：本部署就它的工具声明了什么，以及控制台在哪里改它 —— 仅管理员。
 *
 * <p>这是控制台的策略页，它答冻结的 `governance-policy` 对象、一点没多加：逐工具的覆盖与审计粒度就是它的全部，
 * 外加对象自带的两个「关于策略本身」的键（`updatedAt`、`revision`）—— 这两个是本运行时**填上的**、不是接受的。
 * 所以一次 GET 的结果同时也是一份合法的 PUT 请求体：控制台**读出来再写回去**，不会因为「把它拿到的东西还回来」被拒。
 *
 * <p><b>策略是被施加的，所以它不是一页接不到任何东西的开关。</b> 它禁用的工具、改动的门控档位、收窄的角色白名单，
 * 都落在引擎每次调用都要过的那道闸上（`GovernanceService.decide`）；写下一份策略改变的是**接下来会发生什么**，不只是
 * 一个页面显示什么。存储**不会**接受一项它施加不了的设置 —— 见 `GovernancePolicyStore` —— 故一份点名了本部署**没有
 * 注册**的工具、用了冻结三值之外的 `mode`、或点名了本部署**词表之外**角色的提交，会带着理由回一个 `400`，而不是被
 * 存下来、再被悄悄忽略。
 */
@RestController
public class GovernancePolicyController {

    private final GovernancePolicyStore policy;
    private final CurrentPrincipal principals;

    public GovernancePolicyController(GovernancePolicyStore policy, CurrentPrincipal principals) {
        this.policy = policy;
        this.principals = principals;
    }

    @GetMapping("/ai/governance/policy")
    public Map<String, Object> read() {
        requireAdministrator();
        return policy.current();
    }

    @PutMapping("/ai/governance/policy")
    public Map<String, Object> write(@RequestBody Map<String, Object> body) {
        requireAdministrator();
        return policy.set(body);
    }

    /** The policy says what governance is doing to every tool, so it is an administrator's surface. */
    private void requireAdministrator() {
        Principal caller = principals.current();
        if (!caller.isManager()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "the governance policy is readable and writable by an administrator");
        }
    }
}
