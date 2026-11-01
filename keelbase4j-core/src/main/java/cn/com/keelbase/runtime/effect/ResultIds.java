// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

/**
 * How a stored result id is read back out: as a number on the wire when it is one, as text otherwise.
 *
 * <p>The ledger stores the id as text, because a business object's id is not always a number — a
 * Flowable process instance is a UUID. The wire is a different question, and its answer has to stay
 * the one it was: every effect recorded before text was allowed carries an integer id, and the frozen
 * {@code side-effect-revoke} object types {@code resultId} as a number. Handing those back as
 * {@code "42"} would be a wire change nobody asked for, made by a storage decision.
 *
 * <p>So the rule is narrow and stated once, here: a stored id that is the canonical decimal form of a
 * {@code long} is written as that number; anything else is written as the text it is. Nothing is
 * parsed back into a number to "look numeric" — an id is what it was stored as, and only its shape on
 * the wire depends on whether that shape can carry it.
 *
 * 存下的 result id 怎么被读回去：是数字时在 wire 上写成数字，否则写成文本。
 *
 * <p>账本把 id 存成文本，因为业务对象的 id 不总是数字 —— Flowable 流程实例是 UUID。**wire 是另一个问题**，
 * 而它的答案必须保持原样：在文本被允许之前记下的每条 effect 都带整数 id，而冻结的 `side-effect-revoke` 对象把
 * `resultId` 定为数字。把这些写回 `"42"` 是一次没人要求过的 wire 改动，起因却是一个存储决定。
 *
 * <p>所以规则很窄、只说一次，就在这里：存下的 id 若是某个 `long` 的**规范十进制写法**，就写成那个数字；
 * 其它一律写成它本来就是的文本。这里不把任何东西解析回数字去「显得像个数字」—— **id 就是它被存进去的样子**，
 * 只有它在 wire 上的形状取决于那个形状装不装得下它。
 */
public final class ResultIds {

    private ResultIds() {
    }

    /**
     * The id as the wire should carry it: a {@code Long} for a canonical decimal id, the text
     * otherwise.
     *
     * 这个 id 在 wire 上该有的样子：规范十进制 id 给 `Long`，其余给文本。
     */
    public static Object wire(String resultId) {
        Long number = asLong(resultId);
        return number == null ? resultId : number;
    }

    /**
     * The id as a number, or {@code null} when it is not one.
     *
     * <p>Canonical form only, for the same reason the turn service's slot uses it: {@code "007"} is
     * text, and reading it as {@code 7} would hand a reader a value that was never stored.
     *
     * 这个 id 作为数字，或不是数字时的 {@code null}。
     *
     * <p>只认规范写法，理由与回合服务的槽位相同：`"007"` 是文本，把它读成 `7` 等于把一个**从未存过**的值
     * 交给读它的人。
     */
    public static Long asLong(String resultId) {
        if (resultId == null) {
            return null;
        }
        try {
            long number = Long.parseLong(resultId);
            return Long.toString(number).equals(resultId) ? number : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
