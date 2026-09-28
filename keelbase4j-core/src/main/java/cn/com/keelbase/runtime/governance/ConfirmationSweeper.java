// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The cleanup task the offline window depends on.
 *
 * <p>v2 made the in-conversation wait stop ending confirmations, which is only safe because something
 * else eventually does. That something is this: periodically, every {@code pending} confirmation
 * whose offline window has closed becomes {@code timeout}. Without it the wait window would be a
 * promise the runtime could not keep — a confirmation would stay decidable forever, and the row would
 * never reach a terminal state.
 *
 * <p>The interval is deliberately much shorter than the window: the sweep is cheap, and the gap
 * between a window closing and being noticed is only worth minimising.
 */
@Component
public class ConfirmationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationSweeper.class);

    private final ConfirmationStore confirmations;

    public ConfirmationSweeper(ConfirmationStore confirmations) {
        this.confirmations = confirmations;
    }

    @Scheduled(fixedDelayString = "${keelbase.confirmation.sweep-interval-ms:60000}",
            initialDelayString = "${keelbase.confirmation.sweep-interval-ms:60000}")
    public void sweep() {
        int closed = confirmations.expireStale(Instant.now(), ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS);
        if (closed > 0) {
            log.info("confirmation offline window closed on {} row(s)", closed);
        }
    }
}
