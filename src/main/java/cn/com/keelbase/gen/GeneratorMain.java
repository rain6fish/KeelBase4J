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

    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "target/gen-demo");
        BusinessSpec spec = new BusinessSpecParser().parse(REQUEST);
        var files = new JavaGenerator().generate(spec, out);
        System.out.println("generated " + files.size() + " files into " + out);
    }
}
