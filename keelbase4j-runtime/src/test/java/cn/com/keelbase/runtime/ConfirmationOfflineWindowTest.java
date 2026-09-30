// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
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

        // A `now` strictly after the row, not the wall clock. The window is zero, so the cutoff lands
        // exactly on the row's `createdAt` — and that boundary is half-open (`createdAt < cutoff`
        // expires, `createdAt >= cutoff` stays decidable), so a row stamped in the *same* microsecond
        // as `Instant.now()` has not closed its window yet. Asking the clock for "now" turns this
        // assertion into a race it wins almost always and loses occasionally; asking for one
        // millisecond past the row makes the premise true by construction.
        //
        // 用严格晚于该行的 `now`，而不是墙上时钟。零窗口下 cutoff 正好落在那行的 `createdAt` 上 —— 而
        // 该边界是**半开**的（`createdAt < cutoff` 过期、`createdAt >= cutoff` 仍可裁决），故与
        // `Instant.now()` 落在**同一微秒**的行尚未关窗。向时钟要「现在」会把这条断言变成一场几乎总赢、
        // 偶尔输的竞态；要「该行之后一毫秒」则让前提**按构造**成立。
        int closed = confirmations.expireStale(req.getCreatedAt().plusMillis(1), ALREADY_CLOSED);

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

    /**
     * The boundary is half-open, and both halves have to keep agreeing about it: {@code createdAt <
     * cutoff} expires, {@code createdAt >= cutoff} stays decidable. A row whose {@code createdAt} lands
     * exactly on the cutoff is therefore still inside its window — the sweeper leaves it and a decision
     * may still land. Pinned because the two halves live in different places (a JPQL {@code where}
     * clause and an in-memory filter), so "make the expiry side inclusive" reads like a one-word
     * improvement while putting the same row into both halves at once.
     *
     * <p>边界是半开的，且两半必须**继续一致**：`createdAt < cutoff` 过期、`createdAt >= cutoff` 仍可
     * 裁决。故 `createdAt` 正落在 cutoff 上的行**仍在窗口内** —— 清扫器放过它、裁决仍可落地。之所以钉住
     * 它，是因为两半住在**不同的地方**（一句 JPQL 的 `where` 与一处内存过滤），于是「让过期那侧含边界」
     * 读起来像一字改进，却会把同一行**同时**放进两半。
     */
    @Test
    void aRowExactlyOnTheCutoffIsStillInsideItsWindow() {
        ConfirmationRequest req = pending();

        // The value to ask about is the one the row was *stored* with, not the one the Java object was
        // built with: the column truncates, so the stored instant is the earlier of the two, by up to a
        // microsecond. Asking with the object's value lands the cutoff just past the stored row and
        // would pass for the wrong reason — the boundary would look exclusive on both halves — which is
        // exactly the mistake this test exists to keep out.
        //
        // 该问的是这一行**被存下来**的那个值，而不是 Java 对象构造时的那个：列精度更粗，会**截断**，
        // 存储的瞬间因此比对象里的早（最多一微秒）。拿对象的值去问，cutoff 会落在那行**之后** —— 于是
        // 两半看起来都像排他，测试**因为错的原因**通过，而这正是本测试要挡住的错。
        Instant stored = repository.findByToken(req.getToken()).orElseThrow().getCreatedAt();

        assertEquals(0, confirmations.expireStale(stored, ALREADY_CLOSED),
                "the sweep's `createdAt < cutoff` leaves the row on the boundary alone");

        ConfirmationStore.OutOfBandResult decision = confirmations.decideOutOfBand(
                req.getToken(), new Principal("alice", "user"), ConfirmationLifecycle.DECLINE,
                stored, ALREADY_CLOSED);

        assertEquals(ConfirmationStore.OutOfBand.DECIDED, decision.outcome(),
                "and the decide guard's `createdAt >= cutoff` still lets the decision land");
    }

    /** Dead wiring is the failure mode this guards: a sweeper nothing schedules closes nothing. */
    @Test
    void theSweeperIsWired() {
        assertEquals(1, context.getBeansOfType(ConfirmationSweeper.class).size());
    }

    private ConfirmationRequest pending() {
        return confirmations.create(
                new Principal("alice", "user"), "create_followup", "{}", "R3", ConfirmationMode.IMMEDIATE);
    }

    private String statusOf(ConfirmationRequest req) {
        return repository.findByToken(req.getToken()).orElseThrow().getStatus();
    }
}
