// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.gen.BusinessSpec.ToolSpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * G2 · S1 + S2 acceptance.
 *
 * <p>S1 — a business description yields an explicit, reviewable spec with the required elements.
 * S2 — the spec yields a real Spring Boot project that a developer can open and that actually
 * <b>compiles</b> (checked with the JDK compiler against the current classpath).
 */
class GeneratorTest {

    private static final String REQUEST = """
            帮我做一个客户管理系统：有客户和跟进记录。销售只能看到自己负责的客户，经理能看到全部。
            AI 要能每天分析哪些客户风险高（只读）；AI 想给客户建跟进记录时，必须先让我确认；
            所有 AI 的操作都要能追溯。""";

    @Test
    void s1_business_intent_is_complete() {
        BusinessSpec spec = new BusinessSpecParser().parse(REQUEST);

        assertEquals("crm", spec.module());
        assertEquals(2, spec.entities().size(), "two entities");
        assertTrue(spec.entities().stream().anyMatch(e -> e.name().equals("Customer")));
        assertTrue(spec.entities().stream().anyMatch(e -> e.name().equals("FollowUp")));
        assertTrue(spec.entities().stream().allMatch(e -> !e.fields().isEmpty()), "entities have fields");

        assertNotNull(spec.roleRule());
        assertEquals("ownerUserId", spec.roleRule().ownerField());
        assertTrue(spec.roleRule().description().contains("经理"), "manager rule captured");

        assertEquals(2, spec.tools().size(), "two AI tools");
        ToolSpec read = spec.tools().stream().filter(t -> t.name().equals("analyze_customer_risk")).findFirst().orElseThrow();
        ToolSpec write = spec.tools().stream().filter(t -> t.name().equals("create_followup")).findFirst().orElseThrow();
        assertEquals("R1", read.riskLevel());
        assertFalse(read.requiresConfirmation(), "read tool needs no confirmation");
        assertEquals("R3", write.riskLevel());
        assertTrue(write.requiresConfirmation(), "write tool requires confirmation");
    }

    @Test
    void s2_generates_a_real_project_that_compiles() throws IOException {
        BusinessSpec spec = new BusinessSpecParser().parse(REQUEST);
        Path out = Path.of("target", "gen-test");
        deleteRecursively(out);

        List<Path> written = new JavaGenerator().generate(spec, out).files();

        // The generator produced ordinary source files.
        List<String> paths = written.stream().map(p -> p.toString().replace('\\', '/')).toList();
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("pom.xml")), "pom.xml");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("domain/Customer.java")), "Customer.java");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("domain/FollowUp.java")), "FollowUp.java");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/AnalyzeCustomerRiskTool.java")), "read tool");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/CreateFollowupTool.java")), "write tool");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/GovernanceEngine.java")), "engine");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/ConfirmationStore.java")), "confirmations");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/AuditChainStore.java")), "audit");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("web/AiController.java")), "ai controller");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("web/GovernanceController.java")), "governance controller");
        // The identity seam and the contract-derived authorization, not a bespoke role check.
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("identity/IdentityResolver.java")), "identity SPI");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("identity/HeaderIdentityResolver.java")), "adapter");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("authz/AuthorizationRules.java")), "rule source");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("authz/PermissionAuthorizer.java")), "decision");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("authz/OwnershipGuard.java")), "row guard");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("web/AuthController.java")), "identity surface");
        // The schema is migrated, not re-created.
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("db/migration/V1__crm_baseline.sql")),
                "baseline migration");
        String baseline = Files.readString(out.resolve("src/main/resources/db/migration/V1__crm_baseline.sql"));
        assertTrue(baseline.contains("CREATE TABLE customers"), "the baseline creates the table");
        assertTrue(baseline.contains("owner_user_id VARCHAR(255)"), "with the ownership column");
        String properties = Files.readString(out.resolve("src/main/resources/application.properties"));
        assertTrue(properties.contains("jdbc:h2:file:"), "a database that outlives the process");
        assertTrue(properties.contains("ddl-auto=validate"), "Flyway owns the schema; Hibernate checks it");

        String customer = Files.readString(out.resolve("src/main/java/com/example/crm/domain/Customer.java"));
        assertTrue(customer.contains("@Entity"), "a real JPA entity");
        assertTrue(customer.contains("class Customer"), "a real class");

        // The generated project consumes a *published* coordinate, so the version in its pom has to be
        // the one this build publishes. It is filtered from the project version for that reason; this
        // pins the emission, because a literal written back into the template would be the silent way
        // to drift — and the failure would land in the generated project's build instead of here.
        String pom = Files.readString(out.resolve("pom.xml"));
        Matcher protocolDependency = Pattern.compile(
                        "<artifactId>keelbase4j-protocol</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(protocolDependency.find(), "the generated pom depends on the protocol library");
        String filtered = versionThisBuildPublishes();
        // Both sides read the same file, so a filtering regression would leave the raw placeholder on
        // both and satisfy the equality below. Asserting the value is a version, not a placeholder, is
        // what keeps that from being a passing test of nothing. The generator's own reader has the
        // same guard, which is the end-to-end version of this check.
        assertFalse(filtered.contains("@") || filtered.contains("$"),
                "the build must have filtered the placeholder out: " + filtered);
        assertEquals(filtered, protocolDependency.group(1),
                "and the emitted dependency is on the version this build publishes, not a literal");

        // The generated project compiles as real Java source.
        List<File> sources = javaFiles(out.resolve("src/main/java"));
        assertFalse(sources.isEmpty(), "no sources generated");
        Path classes = out.resolve("target/classes");
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to compile generated sources");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromFiles(sources);
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString());
            boolean ok = compiler.getTask(null, manager, diagnostics, options, null, units).call();
            assertTrue(ok, () -> "generated sources failed to compile:\n" + diagnostics.getDiagnostics().stream()
                    .map(Object::toString).collect(Collectors.joining("\n")));
        }
    }

    private static List<File> javaFiles(Path root) throws IOException {
        List<File> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        }
        return files;
    }

    /** What the build filtered into the generator's resources — the one source for the version. */
    private static String versionThisBuildPublishes() throws IOException {
        try (InputStream in = GeneratorTest.class.getResourceAsStream("/keelbase4j-generator.properties")) {
            assertNotNull(in, "the filtered resource must be on the test classpath");
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("protocol.version");
        }
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
