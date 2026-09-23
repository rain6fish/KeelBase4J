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
        // The module carries the name the request gave it, which is what the capability surface reports.
        // It is deliberately not the first entity's name: a module label that names one of its entities
        // is wrong the moment the module has a second one.
        assertEquals("客户管理", spec.moduleLabel());
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
        // Every entity gets its own REST surface, not just the first one. A two-entity spec used to
        // generate a table, a repository and an authorization rule for both, and a controller for one —
        // so the rest of the model was reachable only by hand.
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("web/CustomerController.java")),
                "a controller for the first entity");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("web/FollowUpController.java")),
                "and one for the second");
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

        // The conversation transcript is a migration of its own (ADR-0013 D4) — additive, so it lands
        // as a new version rather than rewriting the baseline.
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("db/migration/V2__add_conversations.sql")),
                "the additive conversation migration");
        String conversation = Files.readString(
                out.resolve("src/main/resources/db/migration/V2__add_conversations.sql"));
        assertTrue(conversation.contains("CREATE TABLE conversation_messages"),
                "it creates the transcript table the chat's conversationId names");
        assertFalse(conversation.contains("ALTER") || conversation.contains("DROP"),
                "and it is additive: it alters and drops nothing");

        String customer = Files.readString(out.resolve("src/main/java/com/example/crm/domain/Customer.java"));
        assertTrue(customer.contains("@Entity"), "a real JPA entity");
        assertTrue(customer.contains("class Customer"), "a real class");

        // The F5 capability surface reports the module's label. It used to report the first entity's
        // name, which a console would show as the module's name.
        String appInfo = Files.readString(out.resolve("src/main/java/com/example/crm/web/AppInfoController.java"));
        assertTrue(appInfo.contains("MODULE_LABEL = \"客户管理\""),
                "the module is labelled as the module, not as one of its entities");

        // Who the caller is comes from a verified delegation token, and the role from the app's own
        // directory. Reading either off the request would let a caller grant itself one — the adapter the
        // runtime removed for that reason (JV-9) — so the header adapter ships, but not as the default.
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("identity/DelegationTokenIdentityResolver.java")),
                "the default adapter verifies the frozen delegation token");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("identity/LocalIdentities.java")),
                "and the role comes from a declared directory");
        String headerResolver = Files.readString(
                out.resolve("src/main/java/com/example/crm/identity/HeaderIdentityResolver.java"));
        assertFalse(headerResolver.contains("@Component"),
                "the header adapter must not be the default: a role from a request is a role the caller grants itself");

        // The two AI seams and the transcript behind conversationId (ADR-0013). All of it is generated
        // source, so the app answers a message with no model and still depends only on the protocol
        // library — and it converges on the runtime's conversation shape rather than inventing one.
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/ToolCallPlanner.java")), "planner seam");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/Proposal.java")), "a proposal type");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/RuleBasedPlanner.java")), "the default planner");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/ChatReplier.java")), "replier seam");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/Reply.java")), "a reply type");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/ChatTurn.java")), "a turn type");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/DeterministicReplier.java")),
                "the deterministic default replier");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("ai/ChatPipelineConfiguration.java")),
                "one configuration registering both defaults, each conditional");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("conversation/ConversationMessage.java")),
                "the transcript entity");
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("conversation/ConversationStore.java")),
                "the conversation store");

        // The default planner routes on the words the spec carries — the request's own — one branch per
        // tool; and the deterministic replier identifies itself instead of implying a model wrote it.
        String planner = Files.readString(out.resolve("src/main/java/com/example/crm/ai/RuleBasedPlanner.java"));
        assertTrue(planner.contains("text.contains(\"风险\")") && planner.contains("text.contains(\"分析\")"),
                "the read tool is routed on the spec's trigger words");
        assertTrue(planner.contains("new Proposal(\"create_followup\""),
                "and the write tool likewise, from the same spec");
        String replier = Files.readString(
                out.resolve("src/main/java/com/example/crm/ai/DeterministicReplier.java"));
        assertTrue(replier.contains("PROVIDER = \"deterministic\""), "the default reply declares no model");
        assertTrue(replier.contains("MODEL = \"none\""), "and names itself deterministic/none");

        // `/ai/chat` takes a message and answers the conversation shape the runtime answers, field for
        // field. The tool-name-in path it replaced must be gone — this is a convergence, not a second
        // endpoint, and an entry that still took a tool name would be the old shape living on.
        String ai = Files.readString(out.resolve("src/main/java/com/example/crm/web/AiController.java"));
        assertTrue(ai.contains("body.get(\"message\")"), "the chat takes a message");
        for (String field : List.of("conversationId", "reply", "provider", "model", "toolCalls",
                "status", "data", "token", "effectId", "error")) {
            assertTrue(ai.contains("answer.put(\"" + field + "\""),
                    "/ai/chat must answer the reference field '" + field + "'");
        }
        assertTrue(ai.contains("planner.plan(message, context)"), "a turn goes through the planner");
        assertTrue(ai.contains("replier.reply("), "and is answered by the replier");
        assertFalse(ai.contains("getOrDefault(\"tool\""),
                "the tool-name-in shape must be gone, not kept alongside");
        assertTrue(ai.contains("@GetMapping(\"/ai/tools\")"), "the tool list stays");

        // The effects list is the console's, not this application's: the envelope and the fields the
        // console's own model requires. A bare array compiles, runs and looks fine — it is only the
        // console that can neither page nor render it, which is why this is asserted on the emission.
        String governance = Files.readString(
                out.resolve("src/main/java/com/example/crm/web/GovernanceController.java"));
        for (String key : List.of("total", "page", "limit", "items")) {
            assertTrue(governance.contains("body.put(\"" + key + "\""),
                    "the effects list must answer the console's envelope field '" + key + "'");
        }
        for (String field : List.of("id", "toolName", "conversationId", "resultType", "resultId",
                "argsHash", "createdAt", "targetExists", "targetSoftDeleted", "targetTitle")) {
            assertTrue(governance.contains("view.put(\"" + field + "\""),
                    "a console row requires '" + field + "'");
        }
        assertFalse(governance.contains("public List<SideEffectStore.Effect> effects"),
                "the bare array is the shape that was replaced, not kept alongside it");
        // The trailing `;` is the assertion, not decoration: `MAX_PAGE_SIZE = 100` is a substring of
        // `= 1000`, so without it a cap raised to a thousand would still satisfy this check.
        assertTrue(governance.contains("MAX_PAGE_SIZE = 100;"),
                "the console cannot ask for the whole table by asking for a big limit");
        // Revoking has to compensate, or `local_compensate` would be a class this application claims
        // and does not honour — a console button that only flips a flag.
        assertTrue(governance.contains("setDeletedAt(Instant.now())"),
                "revoke soft-deletes the row the effect created");
        // The tool's *declared* result type, not its name: the console groups effects by what they made.
        String writeTool = Files.readString(
                out.resolve("src/main/java/com/example/crm/ai/CreateFollowupTool.java"));
        assertTrue(writeTool.contains("return \"follow_up\";"),
                "the write tool answers the spec's result type here");

        // The README has to say what the answer is, or "deterministic fallback" reads as a model the
        // deployment forgot to configure.
        String readme = Files.readString(out.resolve("README.md"));
        assertTrue(readme.contains("## Conversation"), "the README documents the conversation shape");
        assertTrue(readme.contains("`provider` is `deterministic`"),
                "including that the default reply names itself as no model");
        assertTrue(readme.contains("routes on"), "and the words the fallback routes on");
        assertTrue(readme.contains("## Side effects"), "the README documents the effects surface");
        assertTrue(readme.contains("`conversationId` is **null**"),
                "including the field left null, and why it is null rather than made up");

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
