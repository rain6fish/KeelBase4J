// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * G3 · S5 acceptance — changeability.
 *
 * <p>A change request must flow consistently: semantic change → code change → (schema via JPA) →
 * tests → a still-runnable app — <b>and regeneration must not clobber hand-written code.</b> This is
 * the spike's kill gate: if this cannot hold, the generator is a demo generator, not a platform.
 */
class ChangeabilityTest {

    private static final String V1 = """
            帮我做一个客户管理系统：有客户和跟进记录。销售只能看到自己负责的客户，经理能看到全部。
            AI 要能每天分析哪些客户风险高（只读）；AI 想给客户建跟进记录时，必须先让我确认；
            所有 AI 的操作都要能追溯。""";

    private static final String CHANGE = "给客户增加客户等级字段 tier，只有经理才能修改客户。";

    @Test
    void s5_change_stays_consistent_and_preserves_hand_edits() throws IOException {
        Path out = Path.of("target", "s5-app");
        deleteRecursively(out);
        JavaGenerator generator = new JavaGenerator();
        BusinessSpecParser parser = new BusinessSpecParser();

        // 1. v1 — generate.
        BusinessSpec v1 = parser.parse(V1);
        generator.generate(v1, out);
        Path entity = out.resolve("src/main/java/com/example/crm/domain/Customer.java");
        String base = Files.readString(entity);
        assertFalse(base.contains("tier"), "v1 must not have the tier field yet");

        // 2. A developer hand-edits inside the user-code region.
        String edited = base.replace(
                JavaGenerator.USER_BEGIN,
                JavaGenerator.USER_BEGIN + "\n    public String displayName() { return getName(); }");
        Files.writeString(entity, edited, StandardCharsets.UTF_8);

        // 3. A change request → spec v2 → regenerate into the SAME directory.
        BusinessSpec v2 = parser.applyChange(v1, CHANGE);
        generator.generate(v2, out);

        // 4. The change applied, the hand edit survived.
        String regenerated = Files.readString(entity);
        assertTrue(regenerated.contains("tier"), "the new field must be present after regeneration");
        assertTrue(regenerated.contains("displayName"),
                "the developer's hand-written code must survive regeneration");

        // 5. The new rule is enforced in generated code — as contract data, not as a role string. The
        //    policy removed `update` from the user's grant on the entity it names, and the controller
        //    asks the guard rather than deciding for itself.
        Path rules = out.resolve("src/main/java/com/example/crm/authz/AuthorizationRules.java");
        assertTrue(Files.exists(rules), "a rule source must be generated");
        String rulesSource = Files.readString(rules);
        assertTrue(rulesSource.contains(
                        "new Rule(\"Customer\", List.of(\"create\", \"read\", \"delete\")"),
                "the policy must remove update from the user's grant on Customer");

        Path controller = out.resolve("src/main/java/com/example/crm/web/CustomerController.java");
        assertTrue(Files.exists(controller), "a policy controller must be generated");
        String controllerSource = Files.readString(controller);
        assertTrue(controllerSource.contains("ownership.requireAccess"),
                "the update path must ask the authorization guard");
        assertFalse(controllerSource.contains("isManager"),
                "the controller must not carry its own role check");

        // 5b. The schema evolved as a migration rather than a re-create: the applied baseline is left
        //     untouched and the change lands as a new, additive one.
        Path migrations = out.resolve("src/main/resources/db/migration");
        String baseline = Files.readString(migrations.resolve("V1__crm_baseline.sql"));
        assertFalse(baseline.contains("tier"), "an applied migration must not be rewritten");
        String added = Files.readString(migrations.resolve("V2__add_customers_tier.sql"));
        assertTrue(added.contains("ADD COLUMN tier"), "the change adds the column");
        assertFalse(added.contains("DROP"), "and drops nothing");
        assertFalse(added.contains("CREATE TABLE"), "and re-creates nothing");

        // 6. The regenerated project still compiles as real source.
        compileGenerated(out.resolve("src/main/java"));

        // 7. Regeneration is idempotent — same spec ⇒ identical files (no churn, no clobbering).
        Map<String, String> before = readAll(out.resolve("src/main/java"));
        generator.generate(v2, out);
        assertEquals(before, readAll(out.resolve("src/main/java")), "regeneration must be idempotent");

        // 8. The migration history did not grow either: regenerating an unchanged spec must not append
        //    a version, or every run would accrete migrations.
        List<String> migrationNames;
        try (var stream = Files.list(migrations)) {
            migrationNames = stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("V1__crm_baseline.sql", "V2__add_customers_tier.sql"), migrationNames,
                "regeneration must not append a migration");
    }

    private static void compileGenerated(Path root) throws IOException {
        List<File> sources = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toFile()));
        }
        Path classes = root.getParent().getParent().resolve("target/classes");
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromFiles(sources);
            boolean ok = compiler.getTask(null, manager, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, units).call();
            assertTrue(ok, () -> "regenerated sources failed to compile:\n"
                    + diagnostics.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n")));
        }
    }

    private static Map<String, String> readAll(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return files;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
