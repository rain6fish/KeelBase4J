// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import java.nio.file.Path;

/**
 * Dev/CI entry point: generate the spike project from the built-in business request.
 *
 * <p>Keeping the request in code (rather than passing it on the command line) avoids console
 * encoding issues on Windows and keeps the demo deterministic.
 */
public final class GeneratorMain {

    private static final String REQUEST = """
            帮我做一个客户管理系统：有客户和跟进记录。销售只能看到自己负责的客户，经理能看到全部。
            AI 要能每天分析哪些客户风险高（只读）；AI 想给客户建跟进记录时，必须先让我确认；
            所有 AI 的操作都要能追溯。""";

    private GeneratorMain() {
    }

    /** A change request (used when the second argument is {@code changed}). */
    private static final String CHANGE = "给客户增加客户等级字段 tier，只有经理才能修改客户。";

    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "target/gen-demo");
        boolean changed = args.length > 1 && "changed".equals(args[1]);

        BusinessSpecParser parser = new BusinessSpecParser();
        BusinessSpec spec = parser.parse(REQUEST);
        if (changed) {
            spec = parser.applyChange(spec, CHANGE);
        }
        JavaGenerator.Generation generation = new JavaGenerator().generate(spec, out);
        System.out.println("generated " + generation.files().size() + " files into " + out
                + (changed ? " (change applied)" : ""));
        if (!generation.clean()) {
            // Loud on purpose: a merge that could not be resolved must not read like a clean run.
            System.err.println("could not merge " + generation.conflicts().size() + " file(s):");
            generation.conflicts().forEach(conflict -> System.err.println("  " + conflict));
            System.exit(1);
        }
    }
}
