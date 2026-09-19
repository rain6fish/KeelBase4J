// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.ConfirmationSweeper;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The offline window: the thing v2 introduced so that leaving a conversation does not make a
 * confirmation undecidable forever.
 *
 * <p>The window is only half the story — something has to close it. These tests are about the closing:
 * that a window which has not closed is left alone, that one which has closed ends the confirmation,
 * and that a decision always outranks the sweeper. The last one is the load-bearing one: the sweep is
 * a conditional update on {@code status = 'pending'}, so it can never overwrite an approval or a
 * decline that landed while it was running.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmationOfflineWindowTest {

    /** A zero window: by the time it opens, every pending row is already past it. */
    private static final long ALREADY_CLOSED = 0L;

    @Autowired
    ConfirmationStore confirmations;

    @Autowired
    ConfirmationRequestRepository repository;

    @Autowired
    ApplicationContext context;

    /**
     * The sweep is a bulk update over a table the whole test context shares, so the count it returns
     * is only meaningful from an empty table. Without this, a row left pending by another test would
     * be swept here and counted as this test's.
     */
    @BeforeEach
    void onlyThisTestHasPendingConfirmations() {
        repository.deleteAll();
    }

    @Test
    void aWindowThatHasNotClosedIsLeftAlone() {
        ConfirmationRequest req = pending();

        int closed = confirmations.expireStale(Instant.now(), ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS);

        assertEquals(0, closed, "a confirmation inside its offline window is not swept");
        assertEquals(ConfirmationLifecycle.PENDING, statusOf(req),
                "and it stays decidable — that is the whole point of the window");
    }

    @Test
    void aWindowThatHasClosedEndsTheConfirmation() {
        ConfirmationRequest req = pending();

        int closed = confirmations.expireStale(Instant.now(), ALREADY_CLOSED);

        assertEquals(1, closed);
        assertEquals(ConfirmationLifecycle.TIMEOUT, statusOf(req),
                "the offline window is what ends an undecided confirmation");
        assertNotNull(repository.findByToken(req.getToken()).orElseThrow().getDecidedAt(),
                "the sweep records when it happened");
    }

    @Test
    void aDecisionOutranksTheSweeper() {
        ConfirmationRequest req = pending();
        req.setStatus(ConfirmationLifecycle.APPROVED);
        req.setDecidedAt(Instant.now());
        confirmations.save(req);

        int closed = confirmations.expireStale(Instant.now(), ALREADY_CLOSED);

        assertEquals(0, closed,
                "status is part of the where clause, so a decided row is never selected");
        assertEquals(ConfirmationLifecycle.APPROVED, statusOf(req),
                "the sweep must never overwrite a decision");
    }

    /** Dead wiring is the failure mode this guards: a sweeper nothing schedules closes nothing. */
    @Test
    void theSweeperIsWired() {
        assertEquals(1, context.getBeansOfType(ConfirmationSweeper.class).size());
    }

    private ConfirmationRequest pending() {
        return confirmations.create(new Principal("alice", "user"), "create_followup", "{}", "R3");
    }

    private String statusOf(ConfirmationRequest req) {
        return repository.findByToken(req.getToken()).orElseThrow().getStatus();
    }
}
