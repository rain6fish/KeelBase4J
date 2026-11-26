// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The bounds on the audit read face, in rows: a page's default and maximum size, and how far into the
 * chain a caller may start. They are deployment settings, not protocol fields — the frozen
 * {@code ai-audit-log-row} shape does not change with them.
 *
 * <p><b>The contract is "a page is at most {@code maxLimit} rows", not "a request over the cap is
 * refused".</b> An over-asked page is served at the cap, a {@code limit} below one answers one row,
 * a negative {@code offset} starts at the newest row, and an offset past the cap is served from the
 * cap. Clamping keeps a client's paging cursor working across a request that guessed too big; a 400
 * would turn that guess into a dead end. The cap is applied in {@code AuditService}, the seam every
 * read travels, so a caller that reaches the service directly cannot exceed it either.
 *
 * <p>审计读面的行数上限：一页的默认值与最大值，以及调用方在链上最远能从哪一行开始。它们是**部署设置**、
 * 不是协议字段 —— 冻结的 {@code ai-audit-log-row} 形状不随它们改变。
 *
 * <p><b>契约是「一页至多 {@code maxLimit} 行」，不是「超额请求被拒绝」。</b> 超额的一页按上限作答，
 * {@code limit} 小于一答一行，负的 {@code offset} 从最新一行开始，超过上限的 offset 从上限处开始。钳制
 * 让客户端的翻页游标在**猜大了一次**的请求之后仍然可用；400 会把这次猜测变成死路。上限施加在
 * {@code AuditService} —— 每一次读都要走的那个接缝上，故直接够到服务的调用方也越不过它。
 */
@Component
public class AuditReadLimits {

    /** The reference's own query answers 50 rows by default. / 参照实现自己那条查询默认答 50 行。 */
    static final int DEFAULT_LIMIT = 50;

    /** The reference's own query caps a page at 200. / 参照实现自己那条查询一页封顶 200。 */
    static final int MAX_LIMIT = 200;

    /**
     * How far into the chain a caller may start. The rows before {@code offset} are fetched and
     * dropped (see {@code AuditService.rows}), so this is what keeps that fetch bounded.
     *
     * 调用方能从链上多深开始。{@code offset} 之前的行是**取回来再丢掉**的（见 {@code AuditService.rows}），
     * 故这个数正是那次取数有界的原因。
     */
    static final int MAX_OFFSET = 1000;

    private final int defaultLimit;
    private final int maxLimit;
    private final int maxOffset;

    @Autowired
    public AuditReadLimits(
            @Value("${keelbase.audit.logs.default-limit:50}") int defaultLimit,
            @Value("${keelbase.audit.logs.max-limit:200}") int maxLimit,
            @Value("${keelbase.audit.logs.max-offset:1000}") int maxOffset) {
        // A deployment that mistyped one of these gets a start-up refusal naming the setting, not a
        // read face that silently answers something else than the numbers it was given.
        // 部署方把其中一项打错时，得到的是一个点名该设置的启动期拒绝，而不是一条**悄悄答成别的数字**的读面。
        if (defaultLimit < 1) {
            throw new IllegalArgumentException(
                    "keelbase.audit.logs.default-limit must be at least 1, got " + defaultLimit);
        }
        if (maxLimit < 1) {
            throw new IllegalArgumentException(
                    "keelbase.audit.logs.max-limit must be at least 1, got " + maxLimit);
        }
        if (maxOffset < 0) {
            throw new IllegalArgumentException(
                    "keelbase.audit.logs.max-offset must be 0 or more, got " + maxOffset);
        }
        // window() adds the two, so their sum must stay an int; otherwise PageRequest would get a
        // negative size on every request instead of this constructor refusing the setting.
        //
        // `window()` 把两者相加，故它们的和必须留在 int 范围；否则每个请求都会把负的页大小交给
        // PageRequest，而不是由这个构造器拒绝该设置。
        if (maxOffset > Integer.MAX_VALUE - maxLimit) {
            throw new IllegalArgumentException(
                    "keelbase.audit.logs.max-offset (" + maxOffset
                            + ") plus keelbase.audit.logs.max-limit (" + maxLimit
                            + ") must not overflow an int");
        }
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException(
                    "keelbase.audit.logs.default-limit (" + defaultLimit
                            + ") must not exceed keelbase.audit.logs.max-limit (" + maxLimit + ")");
        }
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.maxOffset = maxOffset;
    }

    /** The values above, for the constructors that run without a Spring context. / 上面那组值，供不跑 Spring 上下文时的构造器使用。 */
    public static AuditReadLimits defaults() {
        return new AuditReadLimits(DEFAULT_LIMIT, MAX_LIMIT, MAX_OFFSET);
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int maxLimit() {
        return maxLimit;
    }

    public int maxOffset() {
        return maxOffset;
    }

    /**
     * A requested page size, resolved against the default and clamped into {@code [1, maxLimit]}.
     *
     * 所请求的页大小，按默认值解析并钳进 {@code [1, maxLimit]}。
     */
    public int limit(Integer requested) {
        if (requested == null) {
            return defaultLimit;
        }
        return Math.min(Math.max(requested, 1), maxLimit);
    }

    /** A requested start, clamped into {@code [0, maxOffset]}. / 所请求的起点，钳进 {@code [0, maxOffset]}。 */
    public int offset(Integer requested) {
        if (requested == null) {
            return 0;
        }
        return Math.min(Math.max(requested, 0), maxOffset);
    }

    /**
     * How many rows the object filter's scan may select so that the page it feeds is complete: the
     * rows a page spans, newest first. It never exceeds {@code maxLimit + maxOffset}, the deepest
     * window any request can page through.
     *
     * 对象过滤的那次扫描最多可选多少行，好让它喂出的那一页是完整的：一页横跨的行数，最新的在前。它绝不
     * 超过 {@code maxLimit + maxOffset} —— 任何请求能翻到的最深窗口。
     */
    public int window(int limit, int offset) {
        return limit(limit) + offset(offset);
    }
}
