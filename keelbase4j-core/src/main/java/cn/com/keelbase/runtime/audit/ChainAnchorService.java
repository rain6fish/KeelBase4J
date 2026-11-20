// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import cn.com.keelbase.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chain tail, the unsigned anchor payload over it, and the comparison against a known tail.
 *
 * <p>This runtime neither signs nor verifies an anchor — the private key never enters a container
 * that serves requests, and no SM2/SM3 implementation is on its classpath — so what lives here is the
 * two halves that need neither: a read of the chained tail, and the answer to "does the chain still
 * contain the tail someone signed elsewhere". {@link #payload} emits the record <b>without</b> its
 * {@code sm2} segment; it is a payload to be signed and published by the deployer, and it is not an
 * anchor until someone does that.
 *
 * <p><b>Only the AI chain is hash-chained here.</b> The operation audit is a table, deliberately not
 * a second chain ({@code OperationAuditLog} records why), so its half of the record is always
 * {@code null} — which the schema defines as "no chained row at that moment", never "unknown".
 *
 * <p><b>A read is one snapshot.</b> {@code rows} and the last row must come from the same instant, or
 * a row written between the two queries would be counted without being seen. Both reads therefore run
 * in one repeatable-read transaction.
 *
 * <p>链尾、以链尾为内容的未签名锚载荷、以及与已知链尾的对照。
 *
 * <p>本运行时**不签名、也不验签** —— 私钥不进处理请求的容器，classpath 上也没有 SM2/SM3 实现 —— 所以
 * 这里只放两件不需要它们的事：读一次已入链的链尾，以及回答「链是否仍包含别人在域外签过的那组链尾」。
 * {@link #payload} 产出**不带 `sm2` 段**的记录：那是交给部署方签名与发布的载荷，在有人签名之前**它不是锚**。
 *
 * <p>**这里只有 AI 链是哈希链。** 操作审计是一张表，有意不做第二条链（理由记在 {@code OperationAuditLog}），
 * 故记录的它那一半永远是 `null` —— schema 把 `null` 定义为「该时刻该链没有已入链的行」，绝不是「未知」。
 *
 * <p>**一次读取就是一个快照。** `rows` 与末行必须来自同一时刻，否则两次查询之间写下的行会被计数、却看不见。
 * 故两次读取同在一个可重复读事务里。
 */
@Service
public class ChainAnchorService {

    /** The wire {@code period} this runtime produces; the contract currently defines only this one. */
    public static final String PERIOD_DAILY = "daily";

    /** Statuses, named exactly as the reference names them — a console on either runtime reads one vocabulary. */
    public static final String EMPTY = "empty";
    public static final String OK = "ok";
    public static final String GREW = "grew";
    public static final String TAIL_MISSING = "tail-missing";
    public static final String LAST_ROW_CHANGED = "last-row-changed";
    public static final String TAIL_REPLACED = "tail-replaced";

    /** Fixed to milliseconds and {@code Z}, which is the form the contract's sample uses. */
    private static final DateTimeFormatter RFC3339_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * One chain's tail: how many rows are chained, and which row is last.
     *
     * 一条链的链尾：有多少行已入链、末行是哪一行。
     */
    public record Tail(long rows, long lastId, String lastHash) {
    }

    /**
     * The two chains the record commits to. {@code operationAudit} is always null here, because that
     * table is not a chain — see the class javadoc.
     *
     * 记录承诺的两条链。这里的 `operationAudit` 永远为 null，因为那张表不是链 —— 见类注释。
     */
    public record Tails(Tail aiAudit, Tail operationAudit) {
    }

    /**
     * One chain's answer: {@code ok} says the tail is still there, the status says which of the six
     * states it is, and {@code reason} is the sentence an operator reads.
     *
     * 一条链的答案：`ok` 说链尾还在，`status` 说六种状态里的哪一种，`reason` 是运维读到的那句话。
     */
    public record ChainComparison(String chain, boolean ok, String status, String reason,
                                  Tail anchored, Tail current) {
    }

    /** Both chains' answers, with the overall verdict and the named reasons. */
    public record Comparison(boolean ok, List<String> reasons, ChainComparison aiAudit,
                             ChainComparison operationAudit) {
    }

    private final AuditLogRepository audits;

    public ChainAnchorService(AuditLogRepository audits) {
        this.audits = audits;
    }

    /** The chained tails as they stand now, read as one snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Tails tails() {
        return readNow();
    }

    /**
     * The record over the current tail, <b>without</b> {@code sm2}: the deployer signs it outside this
     * runtime and publishes it. Naming it an anchor before that would be the lie the reference refuses
     * to tell.
     *
     * 以当前链尾为内容的记录，**不带 `sm2`**：部署方在本运行时之外签名并发布。在那之前把它叫成锚，
     * 就是参照实现拒绝说的那句谎。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> payload(Instant observedAt) {
        return payloadOf(readNow(), observedAt);
    }

    /**
     * Where the chain stands relative to a tail someone signed elsewhere. The comparison does not
     * check the chain's internal continuity — that is {@code /audit/verify}'s job — it only answers
     * whether the anchored tail is still there.
     *
     * 相对一组在域外签过的链尾，链现在是什么状态。本对照**不**检查链的内部连续性 —— 那是
     * `/audit/verify` 的职责 —— 只回答锚定的链尾还在不在。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Comparison compare(Tails anchored) {
        Tails current = readNow();
        ChainComparison ai = compareOne("aiAudit", anchored.aiAudit(), current.aiAudit(),
                anchored.aiAudit() == null ? null : hashAt(anchored.aiAudit().lastId()));
        ChainComparison operation = compareOne("operationAudit", anchored.operationAudit(),
                current.operationAudit(), null);
        List<String> reasons = new ArrayList<>();
        if (!ai.reason().isEmpty()) {
            reasons.add(ai.reason());
        }
        if (!operation.reason().isEmpty()) {
            reasons.add(operation.reason());
        }
        return new Comparison(ai.ok() && operation.ok(), List.copyOf(reasons), ai, operation);
    }

    /**
     * The record's bytes, built so that {@link CanonicalJson#json} (insertion order, no replacer
     * filtering) reproduces the reference's recursive key sort: every level below is inserted in
     * alphabetical order. {@link CanonicalJson#canonical} cannot be used here — it filters nested
     * keys through the top level's key list and would drop the tail's fields.
     *
     * 记录的字节。用 {@link CanonicalJson#json}（插入序、无替换器过滤）并让每一层都按字典序插入，
     * 以复现参照实现的递归排序。这里**不能**用 {@link CanonicalJson#canonical} —— 它按顶层键表过滤
     * 嵌套键，会把链尾的字段丢掉。
     */
    public static Map<String, Object> payloadOf(Tails tails, Instant observedAt) {
        Map<String, Object> chains = new LinkedHashMap<>();
        chains.put("aiAudit", tailMap(tails.aiAudit()));
        chains.put("operationAudit", tailMap(tails.operationAudit()));
        Map<String, Object> unsigned = new LinkedHashMap<>();
        unsigned.put("chains", chains);
        unsigned.put("date", observedAt.atZone(ZoneOffset.UTC).toLocalDate().toString());
        unsigned.put("observedAt", RFC3339_MILLIS.format(observedAt));
        unsigned.put("period", PERIOD_DAILY);
        Map<String, Object> out = new LinkedHashMap<>(unsigned);
        out.put("rootDigest", sha256Hex(CanonicalJson.json(unsigned)));
        return out;
    }

    /**
     * One chain's comparison, given what the anchor says and what the chain says now.
     *
     * @param anchoredRowHash the hash currently stored for {@code anchored.lastId}, or {@code null}
     *     when no such chained row exists
     *
     * 一条链的对照：锚说什么、链现在说什么。锚定末行在当前链里的 hash 由调用方连库取，取不到即 null。
     */
    static ChainComparison compareOne(String chain, Tail anchored, Tail current, String anchoredRowHash) {
        if (anchored == null) {
            if (current == null) {
                return new ChainComparison(chain, true, EMPTY, "", null, null);
            }
            return new ChainComparison(chain, true, GREW,
                    chain + ": the anchor saw no chained row, the chain has " + current.rows()
                            + " now — added after the anchor, not a deletion", null, current);
        }
        if (current == null) {
            return new ChainComparison(chain, false, TAIL_MISSING,
                    chain + ": anchored " + anchored.rows() + " rows (last id=" + anchored.lastId()
                            + "), the chain has 0 — the tail is missing", anchored, null);
        }
        if (current.rows() < anchored.rows()) {
            return new ChainComparison(chain, false, TAIL_MISSING,
                    chain + ": anchored " + anchored.rows() + " rows, the chain has " + current.rows()
                            + " — missing " + (anchored.rows() - current.rows()), anchored, current);
        }
        if (anchoredRowHash == null) {
            return new ChainComparison(chain, false, LAST_ROW_CHANGED,
                    chain + ": the anchored last row id=" + anchored.lastId()
                            + " is not in the chain — the tail was deleted", anchored, current);
        }
        if (!anchoredRowHash.equals(anchored.lastHash())) {
            return new ChainComparison(chain, false, LAST_ROW_CHANGED,
                    chain + ": the anchored last row id=" + anchored.lastId()
                            + " carries another hash — the last row changed", anchored, current);
        }
        if (current.rows() == anchored.rows()) {
            if (current.lastId() != anchored.lastId()
                    || !current.lastHash().equals(anchored.lastHash())) {
                return new ChainComparison(chain, false, TAIL_REPLACED,
                        chain + ": the same row count (" + anchored.rows() + "), but the last row is "
                                + "id=" + current.lastId() + " instead of id=" + anchored.lastId()
                                + " — the tail was replaced", anchored, current);
            }
            return new ChainComparison(chain, true, OK, "", anchored, current);
        }
        return new ChainComparison(chain, true, GREW,
                chain + ": anchored " + anchored.rows() + " rows, the chain has " + current.rows()
                        + " — " + (current.rows() - anchored.rows())
                        + " rows added after the anchor, not a tail deletion", anchored, current);
    }

    /**
     * Both reads of one snapshot. A row is chained when its hash is set; the schema is `NOT NULL`, but
     * the predicate is what the contract counts, so it is what this asks.
     *
     * 同一快照里的两次读取。hash 有值即已入链；列上有 `NOT NULL`，但契约计的是那个谓词，这里就问那个谓词。
     */
    private Tails readNow() {
        long rows = audits.countByHashIsNotNull();
        Tail ai = rows == 0
                ? null
                : audits.findTopByHashIsNotNullOrderByIdDesc()
                        .map(row -> new Tail(rows, row.getId(), row.getHash()))
                        .orElse(null);
        return new Tails(ai, null);
    }

    private String hashAt(long id) {
        return audits.findById(id).map(AuditLog::getHash).orElse(null);
    }

    private static Map<String, Object> tailMap(Tail tail) {
        if (tail == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastHash", tail.lastHash());
        m.put("lastId", tail.lastId());
        m.put("rows", tail.rows());
        return m;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
