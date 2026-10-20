// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

/**
 * One finding of the integration-health report, in the three pieces an operator can act on: what
 * happened, why, and what to do next.
 *
 * <p>The flat strings the report used to carry named the defect but not the fix. This record is the
 * single source for both representations: {@link #summary()} is what {@code health.errors} /
 * {@code health.warnings} show, and the object itself is what {@code health.diagnostics} carries.
 * Keeping one source is the point — two hand-written copies of the same text drift, and the one a
 * reader sees is then not the one the runtime meant.
 *
 * <p>{@code subject} is the tool, row id or setting the finding is about; it is {@code null} when the
 * finding is about a whole dimension. The text is Chinese, the language of this runtime's actionable
 * copy. No secret, stack trace or SQL appears in any field.
 *
 * <p>接入健康度报告的一条发现，拆成操作方能行动的三块：**发生了什么、为什么、下一步做什么**。
 *
 * <p>报告此前携带的扁平字符串点名了缺陷，却没有给出修法。本记录是两种表示法的**单一来源**：
 * {@link #summary()} 是 `health.errors` / `health.warnings` 展示的东西，对象本身是
 * `health.diagnostics` 携带的东西。保持单一来源正是重点 —— 同一段文字手写两份会漂移，而读的人看到的
 * 那一份就不再是运行时想说的那一份。
 *
 * <p>`subject` 是这条发现涉及的工具、行 id 或设置项；整维度的发现为 `null`。文案用中文 —— 本运行时
 * 可执行文案使用的语言。任何字段都不出现 secret、堆栈或 SQL。
 */
public record IntegrationDiagnostic(String dimension, String severity, String code, String subject,
                                    String what, String why, String nextStep) {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    public static IntegrationDiagnostic error(String dimension, String code, String subject,
                                              String what, String why, String nextStep) {
        return new IntegrationDiagnostic(dimension, ERROR, code, subject, what, why, nextStep);
    }

    public static IntegrationDiagnostic warning(String dimension, String code, String subject,
                                                String what, String why, String nextStep) {
        return new IntegrationDiagnostic(dimension, WARNING, code, subject, what, why, nextStep);
    }

    /**
     * The one-line form used by {@code health.errors} / {@code health.warnings}. It still carries all
     * three pieces, because a reader of the flat list should not have to open the structured one to
     * learn the fix.
     *
     * `health.errors` / `health.warnings` 使用的一行形式。它仍然带着三块内容 —— 读扁平列表的人不该为了
     * 知道怎么修，还必须去翻结构化那份。
     */
    public String summary() {
        return what + "；原因：" + why + "；解决：" + nextStep;
    }
}
