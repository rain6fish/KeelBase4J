// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Who is waiting to hear how a confirmation was decided.
 *
 * <p>A decision arrives on one request and has to reach a stream opened by another, so the runtime
 * needs somewhere to keep the connection between them. That is all this is: a token, and whatever
 * asked to be told about it.
 *
 * <p><b>In-process, and it does not survive a second instance.</b> A stream opened against one
 * instance is invisible to the other, so a decision taken there reaches nobody. This is the same
 * limitation the audit chain's row lock has (JV-8) and it is stated rather than assumed away: one
 * instance, or a shared channel this spike does not have.
 *
 * <p>It deliberately holds callbacks rather than an emitter. Whoever owns the stream decides how to
 * finish it — a decision ends the stream differently from a wait that simply expired — and keeping
 * that logic with the stream is what lets this class stay a registry.
 */
@Component
public class ConfirmationWatchers {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationWatchers.class);

    /** What a stream asked to be told about: decided, or waited out. */
    private record Waiter(Consumer<Map<String, Object>> onDecision, Runnable onExpiry) {
    }

    private final Map<String, Waiter> waiting = new ConcurrentHashMap<>();

    /** Ask to be told when this confirmation is decided, or when its wait runs out. One per token. */
    public void watch(String token, Consumer<Map<String, Object>> onDecision, Runnable onExpiry) {
        waiting.put(token, new Waiter(onDecision, onExpiry));
    }

    /** Stop listening — the stream ended or its caller went away. Idempotent. */
    public void stop(String token) {
        waiting.remove(token);
    }

    /**
     * Tell whoever is waiting what was decided.
     *
     * @return whether anyone was waiting. {@code false} is not an error: a confirmation decided with
     *         no stream open is an ordinary outcome, and the decision stands regardless.
     */
    public boolean decided(String token, Map<String, Object> decision) {
        return deliver(token, waiter -> waiter.onDecision().accept(decision));
    }

    /**
     * The wait ran out with nothing decided — the stream should stop waiting, and close on its own
     * terms rather than be cut off by a connection deadline.
     *
     * <p>This decides nothing. Under v2 an expired wait leaves the confirmation {@code pending}: the
     * write is still the operator's to approve, out of band, for as long as the offline window lasts.
     *
     * @return whether anyone was still waiting, by the same reckoning as {@link #decided}.
     */
    public boolean expired(String token) {
        // Not `Waiter::onExpiry`: as a Consumer that reference reads the Runnable and discards it —
        // a return value adapted to void is dropped, not invoked. The run() has to be explicit.
        return deliver(token, waiter -> waiter.onExpiry().run());
    }

    /**
     * Hand the token's waiter to {@code to}, once, and forget it.
     *
     * <p>Removing before delivering is what makes a decision and an expiry mutually exclusive: they
     * both come through here, so whichever removes the entry delivers, and the other finds nothing.
     * The {@code claim} that decides the confirmation arbitrates which decision gets here at all
     * (JV-20).
     */
    private boolean deliver(String token, Consumer<Waiter> to) {
        Waiter waiter = waiting.remove(token);
        if (waiter == null) {
            return false;
        }
        try {
            to.accept(waiter);
        } catch (RuntimeException brokenStream) {
            // Delivering the news is best-effort; a decision is already recorded and stands, and an
            // expiry was never a fact to lose. Letting this escape would turn a successful approval
            // into a failed request because a browser tab went away.
            log.warn("could not deliver the news about a confirmation to its stream", brokenStream);
        }
        return true;
    }
}
