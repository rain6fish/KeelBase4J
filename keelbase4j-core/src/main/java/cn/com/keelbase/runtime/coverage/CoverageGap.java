// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of the coverage-gap registry — a limitation a statement must declare about itself.
 *
 * <p>The shape mirrors the TypeScript single source (`coverage-gaps.ts`) through the vendored
 * `conformance/coverage-gaps.json`, which is the file this runtime reads. {@code appliesWhen} is a
 * conjunction over the closed fact set; an empty conjunction means "always applies". {@code origin}
 * is internal traceability and does not travel on the wire.
 *
 * <p>覆盖缺口登记表的一条 —— 一份声明必须对自身如实交代的一条限制。
 *
 * <p>形状经 vendored 的 `conformance/coverage-gaps.json` 镜像 TypeScript 单源
 * （`coverage-gaps.ts`），本运行时读的就是那份文件。{@code appliesWhen} 是闭集事实上的**合取**；
 * 空合取表示「恒适用」。{@code origin} 是内部追溯用、**不上 wire**。
 */
public record CoverageGap(
        String id,
        String severity,
        String affects,
        String verdictImpact,
        List<Condition> appliesWhen,
        String statement,
        String origin) {

    /** One conjunct: {@code facts[fact]} must equal {@code expected}. / 一个合取项：事实必须等于期望值。 */
    public record Condition(String fact, Object expected) {
    }

    /** Whether every conjunct holds for {@code facts}. / 该缺口在该组事实下是否适用。 */
    public boolean applies(Map<String, Object> facts) {
        for (Condition condition : appliesWhen) {
            if (!Objects.equals(facts.get(condition.fact()), condition.expected())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Severity order used by every statement, so two runtimes sort the same registry the same way.
     *
     * 每份声明用的严重度顺序，使两个运行时把同一份登记表排成同一个样子。
     */
    public int severityRank() {
        return switch (severity) {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 9;
        };
    }
}
