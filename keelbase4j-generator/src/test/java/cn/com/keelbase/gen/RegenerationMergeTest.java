// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * JV-7 · regeneration must keep hand-written work that lives <b>anywhere</b> in a file, not only
 * inside the marker block, and must say so when it cannot.
 *
 * <p>Each case is chosen so that the previous mechanism would have failed it: an edit outside the
 * markers used to be overwritten without a word, and a file the generator never produced used to be
 * replaced wholesale.
 */
class RegenerationMergeTest {

    private static final String REQUEST = """
            帮我做一个客户管理系统：有客户和跟进记录。销售只能看到自己负责的客户，经理能看到全部。
            AI 要能每天分析哪些客户风险高（只读）；AI 想给客户建跟进记录时，必须先让我确认；
            所有 AI 的操作都要能追溯。""";

    private static final String CHANGE = "给客户增加客户等级字段 tier，只有经理才能修改客户。";

    private final JavaGenerator generator = new JavaGenerator();
    private final BusinessSpecParser parser = new BusinessSpecParser();

    @Test
    void anEditOutsideTheMarkerRegionSurvives() throws IOException {
        Path out = fresh("target/merge-outside");
        BusinessSpec v1 = parser.parse(REQUEST);
        generator.generate(v1, out);

        Path entity = out.resolve("src/main/java/com/example/crm/domain/Customer.java");
        String base = Files.readString(entity);
        assertFalse(base.contains("displayName"), "nothing added yet");

        // Deliberately outside the marker block: this is the edit the old mechanism lost.
        Files.writeString(entity, base.replace(
                "    @Column(name = \"level\")",
                "    // a developer's own note\n    @Column(name = \"level\")"), StandardCharsets.UTF_8);

        JavaGenerator.Generation generation = generator.generate(parser.applyChange(v1, CHANGE), out);

        String regenerated = Files.readString(entity);
        assertTrue(regenerated.contains("// a developer's own note"), "an edit anywhere must survive");
        assertTrue(regenerated.contains("tier"), "and the change still lands");
        assertTrue(generation.clean(), "neither side is contested here");
    }

    @Test
    void editsInDifferentFilesAllSurvive() throws IOException {
        Path out = fresh("target/merge-across-files");
        BusinessSpec v1 = parser.parse(REQUEST);
        generator.generate(v1, out);

        Path entity = out.resolve("src/main/java/com/example/crm/domain/Customer.java");
        Path followUp = out.resolve("src/main/java/com/example/crm/domain/FollowUp.java");
        Files.writeString(entity, Files.readString(entity).replace(
                "    @Column(name = \"level\")",
                "    // note on the customer\n    @Column(name = \"level\")"), StandardCharsets.UTF_8);
        Files.writeString(followUp, Files.readString(followUp).replace(
                "    @Column(name = \"note\")",
                "    // note on the follow-up\n    @Column(name = \"note\")"), StandardCharsets.UTF_8);

        generator.generate(parser.applyChange(v1, CHANGE), out);

        assertTrue(Files.readString(entity).contains("// note on the customer"), "first file kept");
        assertTrue(Files.readString(followUp).contains("// note on the follow-up"), "second file kept");
    }

    @Test
    void aRealConflictIsMarkedAndReportedRatherThanGuessedAt() throws IOException {
        Path out = fresh("target/merge-conflict");
        BusinessSpec v1 = parser.parse(REQUEST);
        generator.generate(v1, out);

        // The change rewrites this README line itself, so an edit to the same line contests it.
        Path readme = out.resolve("README.md");
        Files.writeString(readme, Files.readString(readme).replace(
                "- `Customer` · name:string · level:string",
                "- `Customer` · name:string · level:string (documented by hand)"), StandardCharsets.UTF_8);

        JavaGenerator.Generation generation = generator.generate(parser.applyChange(v1, CHANGE), out);

        assertFalse(generation.clean(), "an unresolved merge must not read like a clean run");
        assertTrue(generation.conflicts().stream().anyMatch(c -> c.contains("README.md")),
                "and must name the file");
        String merged = Files.readString(readme);
        assertTrue(merged.contains("<<<<<<< your changes"), "the file carries diff3 markers");
        assertTrue(merged.contains("(documented by hand)"), "keeping the developer's line");
        assertTrue(merged.contains("tier:string"), "alongside what the generator wanted");
    }

    @Test
    void aFileWeNeverGeneratedIsNotOverwritten() throws IOException {
        Path out = fresh("target/merge-foreign");
        Files.createDirectories(out);
        Path pom = out.resolve("pom.xml");
        Files.writeString(pom, "<project><!-- hand-written, not ours --></project>", StandardCharsets.UTF_8);

        JavaGenerator.Generation generation = generator.generate(parser.parse(REQUEST), out);

        assertFalse(generation.clean(), "no recorded baseline means we cannot merge, and must say so");
        assertTrue(generation.conflicts().stream().anyMatch(c -> c.contains("pom.xml")));
        assertTrue(Files.readString(pom).contains("hand-written, not ours"), "the file is left alone");
    }

    @Test
    void regeneratingWithoutEditsIsCleanAndChangesNothing() throws IOException {
        Path out = fresh("target/merge-idempotent");
        BusinessSpec v1 = parser.parse(REQUEST);
        generator.generate(v1, out);
        String before = Files.readString(out.resolve("src/main/java/com/example/crm/domain/Customer.java"));

        JavaGenerator.Generation again = generator.generate(v1, out);

        assertTrue(again.clean(), "no edits, no conflicts");
        assertTrue(Files.readString(out.resolve("src/main/java/com/example/crm/domain/Customer.java"))
                .equals(before), "and byte-identical output");
    }

    private static Path fresh(String dir) throws IOException {
        Path path = Path.of(dir);
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
        return path;
    }
}
