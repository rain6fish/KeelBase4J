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

    public RuleDeniedException(String deniedBy, String reason) {
        super(HttpStatus.FORBIDDEN, reason);
        this.deniedBy = deniedBy;
    }

    /** The deny basis on the wire ({@code casl}, …) — see the frozen {@code permission-decision}. */
    public String deniedBy() {
        return deniedBy;
    }
}
