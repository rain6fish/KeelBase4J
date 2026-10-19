// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import java.util.List;

/**
 * What a reading covered — the wire shape both runtimes answer.
 *
 * <p>It is the same object the TypeScript console renders: the registry revision it was judged
 * against, the scope it was read over, the sources that were <b>actually consulted</b>, the derived
 * verdict, and the gaps that apply. `origin` and `verdictImpact` stay in the registry — they are
 * ours, not the reader's.
 *
 * <p>一次读法覆盖了什么 —— 两个运行时都答的 wire 形状。
 *
 * <p>它就是 TypeScript 控制台渲染的那个对象：判断所依据的登记表版本、读过的口径、**真的问过**的那些源、
 * 算出的结论、以及适用的缺口。`origin` 与 `verdictImpact` 留在登记表里 —— 它们是我们自己的，不是读者的。
 */
public record CoverageStatement(
        String registryRevision,
        Scope scope,
        List<Source> sources,
        String verdict,
        List<Gap> gaps) {

    /** The window and the caller a statement was read for. / 一份声明所读的时间窗与调用方。 */
    public record Scope(String kind, String since, String to, String subjectRef) {
    }

    /** A source that was read, and how much of it. / 读过的一个源，以及读了多少。 */
    public record Source(String id, String state, Long rows) {
    }

    /** An applicable gap, in the reader's words. / 一条适用缺口，用读者的语言。 */
    public record Gap(String id, String severity, String affects, String statement) {
    }
}
