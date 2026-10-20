// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A 403 that carries <em>why</em>, so the refusal reaches the caller as an explanation rather than a
 * bare status.
 *
 * <p>The decision already knew: {@link cn.com.keelbase.protocol.PermissionDecision#deniedBy()} names
 * the basis, and the frontend turns that value into "what to do about it" (main repo
 * {@code Web-Admin-Vue/src/api/client.ts}, {@code guidanceFor}). Throwing a plain
 * {@link ResponseStatusException} dropped it one frame later — the runtime computed the reason and
 * then told the caller only "forbidden".
 *
 * <p>Extends {@code ResponseStatusException} rather than {@code RuntimeException} so that if the
 * handler rendering the full error body is ever lost, the request still fails as a 403 with the right
 * status instead of turning into a 500.
 */
public class RuleDeniedException extends ResponseStatusException {

    private final String deniedBy;
    private final String subject;
    private final String action;
    private final String nextStep;

    public RuleDeniedException(String deniedBy, String reason) {
        this(deniedBy, reason, null, null, null);
    }

    /**
     * A refusal that also names what was refused and what the caller can do about it.
     *
     * <p>{@code subject} and {@code action} are the capability the guard asked about, not the rules
     * of anyone else. Naming them turns "forbidden" into "this identity lacks {@code read} on
     * {@code IntegrationStatus}", which is the part a caller can act on.
     *
     * 一次同时点名「拒了什么」与「调用方能做什么」的拒绝。
     *
     * <p>`subject` 与 `action` 是守卫问的那个能力，不是别人的规则。点名它们，就把「forbidden」变成
     * 「这个身份对 `IntegrationStatus` 缺 `read`」—— 那才是调用方能行动的部分。
     */
    public RuleDeniedException(String deniedBy, String reason, String subject, String action,
                               String nextStep) {
        super(HttpStatus.FORBIDDEN, reason);
        this.deniedBy = deniedBy;
        this.subject = subject;
        this.action = action;
        this.nextStep = nextStep;
    }

    /** The deny basis on the wire ({@code casl}, …) — see the frozen {@code permission-decision}. */
    public String deniedBy() {
        return deniedBy;
    }

    public String subject() {
        return subject;
    }

    public String action() {
        return action;
    }

    /** The actionable next step, or {@code null} when the guard has nothing narrower than generic. */
    public String nextStep() {
        return nextStep;
    }
}
