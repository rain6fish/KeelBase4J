// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import cn.com.keelbase.gen.BusinessSpec.EntitySpec;
import cn.com.keelbase.gen.BusinessSpec.FieldSpec;
import cn.com.keelbase.gen.BusinessSpec.ToolSpec;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * S2 — turns a {@link BusinessSpec} into an ordinary, <b>self-contained</b> Spring Boot project.
 *
 * <p>The output is real source a developer can open, read and edit: a Maven project that builds and
 * runs on its own. The generator writes files once and is then out of the picture (S3 axis A). The
 * generated app carries its own governance wiring and depends only on the frozen protocol
 * <em>library</em> (risk levels, canonical JSON, audit chain) — not on any KeelBase4J runtime
 * service, so it also runs standalone when that service is absent (S3 axis B).
 */
@Component
public class JavaGenerator {

    /**
     * Generate the project under {@code outDir}, merging every source file against what was generated
     * there last time — see {@link Project}. Returns what was written and what could not be merged.
     */
    public Generation generate(BusinessSpec spec, Path outDir) {
        String pkg = "com.example." + spec.module();
        Path javaDir = outDir.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Path resources = outDir.resolve("src/main/resources");
        Project project = new Project(outDir);

        project.write(outDir.resolve("pom.xml"), pom(spec));
        project.write(outDir.resolve("README.md"), readme(spec));
        project.write(resources.resolve("application.properties"), properties(spec.module()));
        project.write(javaDir.resolve("Application.java"), application(pkg));

        // Schema migrations. The migration history *is* the schema state: the generator reads the
        // migrations already in the project to learn which columns exist, and emits only what is
        // missing. Applied migrations are never rewritten (Flyway records their checksums), so a
        // change becomes a new version that adds to the schema instead of a destructive re-create —
        // which is what lets the rows already in the database survive it.
        Path migrationDir = resources.resolve("db/migration");
        Map<String, Set<String>> applied = appliedColumns(migrationDir);
        boolean fresh = applied.isEmpty();
        if (fresh) {
            project.keep(migrationDir.resolve("V1__" + spec.module() + "_baseline.sql"),
                    migrationBaseline(spec));
        }
        // The conversation transcript (ADR-0013 D4): additive, version-pinned at V2, written once and
        // never rewritten. Kept *before* the diff below so that a later change takes the next version
        // after this one rather than colliding with it — the identity of the change migration and the
        // number the demos pin both depend on that order.
        project.keep(migrationDir.resolve("V2__add_conversations.sql"), conversationMigration());
        // The operations chain (a second audit, of the business writes themselves). Version-pinned at V3
        // and kept before the diff below, the same way V2 is and for the same reason: a later change then
        // takes the number after this one rather than colliding with it.
        //
        // 操作链（第二条审计，记的是**业务写本身**）。版本钉在 V3，且在下面的 diff **之前** keep —— 与 V2
        // 同一做法、同一个理由：后续的变更于是取它**之后**的号，而不是与它撞车。
        project.keep(migrationDir.resolve("V3__operation_audit_log.sql"), operationAuditMigration());
        if (!fresh) {
            Map<String, List<String>> missing = missingColumns(spec, applied);
            if (!missing.isEmpty()) {
                project.keep(
                        migrationDir.resolve("V" + (highestVersion(migrationDir) + 1) + "__"
                                + describe(missing) + ".sql"),
                        migrationAlter(missing));
            }
        }

        for (EntitySpec entity : spec.entities()) {
            project.write(javaDir.resolve("domain/" + entity.name() + ".java"), entityClass(pkg, entity));
            project.write(javaDir.resolve("domain/" + entity.name() + "Repository.java"), repository(pkg, entity));
        }

        // Governance wiring (self-contained; the runtime is generated into the app).
        project.write(javaDir.resolve("ai/AiTool.java"), aiToolInterface(pkg));
        project.write(javaDir.resolve("ai/GovernanceGate.java"), governanceGate(pkg));
        project.write(javaDir.resolve("ai/ToolRegistry.java"), toolRegistry(pkg));
        project.write(javaDir.resolve("ai/ConfirmationStore.java"), confirmationStore(pkg));
        project.write(javaDir.resolve("ai/SideEffectStore.java"), sideEffectStore(pkg));
        project.write(javaDir.resolve("ai/AuditChainStore.java"), auditChainStore(pkg));
        project.write(javaDir.resolve("ai/GovernanceEngine.java"), governanceEngine(pkg));
        // Who is waiting to hear how a confirmation was decided (ADR-0014 D2). A decision arrives on one
        // request and has to reach a stream opened by another; this is the only place that connection
        // lives. In-process, and stated as such.
        project.write(javaDir.resolve("ai/ConfirmationWatchers.java"), confirmationWatchers(pkg));

        for (ToolSpec tool : spec.tools()) {
            project.write(javaDir.resolve("ai/" + pascal(tool.name()) + "Tool.java"), toolClass(pkg, tool, spec));
        }

        // The two AI seams (ADR-0013 D2/D3). A planner proposes what to call, a replier decides what to
        // say; each has a deterministic default and each yields to a deployment's own bean. The
        // defaults are what let the application answer a *message* with no model at all: the spec's own
        // trigger words route it, and the reply describes what the engine actually did.
        project.write(javaDir.resolve("ai/ToolCallPlanner.java"), toolCallPlanner(pkg));
        project.write(javaDir.resolve("ai/Proposal.java"), proposal(pkg));
        project.write(javaDir.resolve("ai/RuleBasedPlanner.java"), ruleBasedPlanner(pkg, spec));
        project.write(javaDir.resolve("ai/ChatReplier.java"), chatReplier(pkg));
        project.write(javaDir.resolve("ai/Reply.java"), reply(pkg));
        project.write(javaDir.resolve("ai/ChatTurn.java"), chatTurn(pkg));
        project.write(javaDir.resolve("ai/DeterministicReplier.java"), deterministicReplier(pkg));
        project.write(javaDir.resolve("ai/ChatPipelineConfiguration.java"), chatPipelineConfiguration(pkg));

        // The conversation transcript behind conversationId (ADR-0013 D4): turns and nothing more. It
        // exists so the id a chat answer carries names something real rather than being invented, and
        // it is deliberately not memory — no embeddings, no retrieval, no memory policy.
        project.write(javaDir.resolve("conversation/ConversationMessage.java"), conversationMessageEntity(pkg));
        project.write(javaDir.resolve("conversation/ConversationMessageRepository.java"),
                conversationMessageRepository(pkg));
        project.write(javaDir.resolve("conversation/ConversationStore.java"), conversationStore(pkg));

        // Identity seam + contract-derived authorization. The same shape the KeelBase4J runtime wires,
        // emitted as this app's own source: the identity is resolved once per request, and every
        // access decision comes out in the frozen permission-* vocabulary rather than from a role
        // string compared inline. The app still depends only on the protocol *library*.
        project.write(javaDir.resolve("identity/Principal.java"), principal(pkg));
        project.write(javaDir.resolve("identity/IdentityEvidence.java"), identityEvidence(pkg));
        project.write(javaDir.resolve("identity/IdentityResolver.java"), identityResolver(pkg));
        project.write(javaDir.resolve("identity/LocalIdentities.java"), localIdentities(pkg));
        // The default adapter is the delegation token one; the header adapter ships too, for deployments
        // behind a guard, but is deliberately not a bean (see its javadoc and JV-9).
        project.write(javaDir.resolve("identity/DelegationTokenIdentityResolver.java"),
                delegationTokenIdentityResolver(pkg));
        project.write(javaDir.resolve("identity/HeaderIdentityResolver.java"), headerIdentityResolver(pkg));
        project.write(javaDir.resolve("authz/AuthorizationRules.java"), authorizationRules(pkg, spec));
        project.write(javaDir.resolve("authz/PermissionAuthorizer.java"), permissionAuthorizer(pkg));
        project.write(javaDir.resolve("authz/OwnershipGuard.java"), ownershipGuard(pkg));

        project.write(javaDir.resolve("web/AiController.java"), aiController(pkg));
        // One turn, two ways of reporting it (ADR-0014 D7): the plain endpoint answers with a body, the
        // streaming one reports the steps as they happen. The turn itself is one implementation — this
        // repository has paid twice for a second one drifting (JV-21 / JV-22).
        project.write(javaDir.resolve("web/ChatTurnService.java"), chatTurnService(pkg));
        project.write(javaDir.resolve("web/ChatStreamController.java"), chatStreamController(pkg));
        project.write(javaDir.resolve("web/ChatStreamConfiguration.java"), chatStreamConfiguration(pkg));
        project.write(javaDir.resolve("web/AuthController.java"), authController(pkg));
        project.write(javaDir.resolve("web/GovernanceController.java"), governanceController(pkg, spec));
        // The console's decision-trace page reads two faces: the caller's own conversations, and the
        // trace of one of them — the turns plus the governance events those turns produced, merged.
        // The governance half is answerable because the conversation id is recorded beside every row
        // the engine writes (ADR: the same link the runtime's conversations/{id}/trace needs).
        //
        // 控制台决策轨迹页读两条面：调用方自己的会话，以及其中一条的追踪 —— 回合，加上那些回合产出的治理事件，
        // 合并而成。治理那一半答得出来，是因为引擎写下的**每一行旁边**都记着会话 id（与运行时的
        // conversations/{id}/trace 需要的是同一条链接）。
        project.write(javaDir.resolve("web/ConversationController.java"), conversationController(pkg));
        // F4 + F5 of the frozen Full profile. Without them the artifact cannot serve the
        // runtime-neutral frontend at all: the frontend unwraps the envelope on every call, and it
        // reads the capability surface before it holds a token.
        project.write(javaDir.resolve("web/WireEnvelope.java"), wireEnvelope(pkg));
        project.write(javaDir.resolve("web/ApiResponseAdvice.java"), apiResponseAdvice(pkg));
        project.write(javaDir.resolve("web/WireErrorController.java"), wireErrorController(pkg));
        project.write(javaDir.resolve("web/WireExceptionHandler.java"), wireExceptionHandler(pkg));
        project.write(javaDir.resolve("web/AppInfoController.java"), appInfoController(pkg, spec));
        // The operations chain: what was done to the business, as distinct from what the AI proposed.
        // A second chain beside the AI one, and its own row shape — the console's operations page reads
        // this one. Emitted whole (entity, repository, interceptor, registration, endpoint) because the
        // generated application depends only on the protocol library and cannot borrow the runtime's.
        //
        // 操作链：**对业务做了什么**，与「AI 提议了什么」相对。与 AI 那条并排的第二条链，有它自己的行形状 ——
        // 控制台的操作审计页读的是这一条。整份发射（实体 · 仓储 · 拦截器 · 注册 · 端点），因为生成物只依赖
        // **协议库**、借不到运行时的。
        project.write(javaDir.resolve("audit/OperationAuditLog.java"), operationAuditLog(pkg));
        project.write(javaDir.resolve("audit/OperationAuditLogRepository.java"),
                operationAuditLogRepository(pkg));
        project.write(javaDir.resolve("web/OperationAuditInterceptor.java"), operationAuditInterceptor(pkg));
        project.write(javaDir.resolve("web/OperationsAuditConfiguration.java"),
                operationsAuditConfiguration(pkg));
        project.write(javaDir.resolve("web/OperationAuditController.java"), operationAuditController(pkg));
        // One CRUD controller per entity, each enforcing the spec's policy rules. Everything else about
        // a second entity was already generated — its table, its repository, and its rule in the
        // authorization source — so leaving its REST surface out made half of the model reachable only
        // by hand, which is not what a generated application means here.
        for (EntitySpec entity : spec.entities()) {
            project.write(javaDir.resolve("web/" + entity.name() + "Controller.java"),
                    entityController(pkg, entity, spec));
        }
        return project.done();
    }

    /**
     * The outcome of one generation.
     *
     * @param files     every path the run wrote or considered
     * @param conflicts paths the merge could not resolve — either written with {@code diff3} conflict
     *                  markers for the developer to settle, or left untouched because there was no
     *                  recorded baseline to merge against at all
     */
    public record Generation(List<Path> files, List<String> conflicts) {

        /** True when every file merged cleanly. */
        public boolean clean() {
            return conflicts.isEmpty();
        }
    }

    /**
     * Per-run file writer. Every source file is a three-way merge: what the generator produced last
     * time (the baseline it records), what the file is now (the developer's, hand-edited anywhere),
     * and what it would produce now. Nothing written by hand is dropped silently — a region both
     * sides changed becomes a marked conflict and is reported rather than guessed at.
     *
     * <p>The marker block the generated sources carry is a <em>suggestion</em> of where to put your
     * code, not the mechanism: an edit anywhere in the file is merged the same way.
     */
    private final class Project {

        private final Path outDir;
        private final Path baselineDir;
        private final List<Path> written = new ArrayList<>();
        private final List<String> conflicts = new ArrayList<>();

        Project(Path outDir) {
            this.outDir = outDir;
            this.baselineDir = outDir.resolve(".keelbase/baseline");
        }

        /** Merge one source file against its recorded baseline and write the result. */
        void write(Path path, String fresh) {
            written.add(path);
            String relative = outDir.relativize(path).toString().replace('\\', '/');
            Path baseline = baselineDir.resolve(relative);
            String mine = readOrNull(path);
            String base = readOrNull(baseline);

            String merged;
            if (mine == null) {
                merged = fresh;
            } else if (base == null) {
                // Nothing here records us producing this file, so the developer's work cannot be told
                // apart from our own output. Overwriting would be a guess about their code.
                conflicts.add(relative + " (no recorded baseline; left untouched)");
                return;
            } else if (mine.equals(base)) {
                merged = fresh;
            } else {
                ThreeWayMerge.Result result;
                try {
                    result = ThreeWayMerge.merge(lines(base), lines(mine), lines(fresh));
                } catch (ThreeWayMerge.TooLargeToMergeException tooLarge) {
                    conflicts.add(relative + " (" + tooLarge.getMessage() + ")");
                    writeFile(baseline, fresh);
                    return;
                }
                if (result.conflicted()) {
                    conflicts.add(relative);
                }
                merged = String.join("\n", result.lines());
            }
            writeFile(path, merged);
            // The baseline tracks what *we* produce, not the merge result: next time, the developer's
            // edits are still a difference against it, so they survive again.
            writeFile(baseline, fresh);
        }

        /** Migrations are immutable: write one the first time and never merge into it. */
        void keep(Path path, String content) {
            written.add(path);
            writeIfAbsent(path, content);
        }

        Generation done() {
            return new Generation(List.copyOf(written), List.copyOf(conflicts));
        }
    }

    private static String readOrNull(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    /** Split on newlines keeping a trailing empty line, so join round-trips the file exactly. */
    private static List<String> lines(String content) {
        return List.of(content.split("\n", -1));
    }

    private static void writeFile(Path path, String content) {
        write(path, content);
    }

    // ── project files ───────────────────────────────────────────────────────────

    private String pom(BusinessSpec spec) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>4.1.1</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.1.0</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-webmvc</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.h2database</groupId>
                      <artifactId>h2</artifactId>
                      <scope>runtime</scope>
                    </dependency>
                    <!-- PostgreSQL, because the deployment this is aimed at runs one, and a driver the
                         project does not declare is not a driver a deployment can add from outside.
                         Both ride along and the URL decides which is used. Flyway's PostgreSQL module
                         comes with them for the same reason the core carries its own: this app runs
                         Flyway itself, and Flyway refuses a database it has no module for. The
                         datasource needs no template change — a URL, or Boot's usual environment
                         variable, points the app at a PostgreSQL. -->
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.flywaydb</groupId>
                      <artifactId>flyway-database-postgresql</artifactId>
                      <scope>runtime</scope>
                    </dependency>
                    <!-- Schema migrations: a change becomes an immutable, versioned migration rather
                         than a re-create, so rows already in the database survive it. Boot 4 has a
                         starter for this; the bare flyway-core library is no longer enough. -->
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-flyway</artifactId>
                    </dependency>
                    <!-- The frozen protocol library (risk levels, canonical JSON, audit chain).
                         A library, not a runtime service — the app runs without any KeelBase4J service. -->
                    <dependency>
                      <groupId>cn.com.keelbase</groupId>
                      <artifactId>keelbase4j-protocol</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(spec.module(), protocolVersion());
    }

    /**
     * The version a generated project depends on, read from the resource Maven filters from this
     * module's own version.
     *
     * <p>It is deliberately not a literal in the template above: that would be a second place to bump
     * at release time, and a template one release behind emits a coordinate nobody can resolve. The
     * failure would surface in the generated project's build rather than in this repository's, which
     * is exactly the kind of drift this project gates elsewhere (see {@code sync-vectors.sh}).
     *
     * <p>Every failure mode here is a build misconfiguration, not a runtime condition: the resource is
     * missing from the classpath only if the packaging changed, and {@code ${project.version}} survives
     * unfiltered only if filtering was switched off. Both are worth stopping on.
     */
    private static String protocolVersion() {
        try (InputStream in = JavaGenerator.class.getResourceAsStream("/keelbase4j-generator.properties")) {
            if (in == null) {
                throw new IllegalStateException("keelbase4j-generator.properties is not on the classpath; "
                        + "the module must package src/main/resources (see its pom)");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("protocol.version");
            if (version == null || version.isBlank() || version.indexOf('@') >= 0
                    || version.indexOf('$') >= 0) {
                throw new IllegalStateException("protocol.version is not filtered: " + version
                        + " — check the module's resource filtering and its delimiter "
                        + "(Spring Boot's parent switches it to '@')");
            }
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read keelbase4j-generator.properties", e);
        }
    }

    private String readme(BusinessSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(spec.module()).append(" — generated by KeelBase4J\n\n");
        sb.append("Generated from a business specification. This is ordinary, self-contained Spring Boot\n");
        sb.append("source code — open it, read it, edit it. The generator is not needed to build or run it,\n");
        sb.append("and it needs no KeelBase4J runtime service: it depends only on the frozen protocol library.\n\n");
        sb.append("## Entities\n\n");
        for (EntitySpec e : spec.entities()) {
            sb.append("- `").append(e.name()).append("`");
            e.fields().forEach(f -> sb.append(" · ").append(f.name()).append(":").append(f.type()));
            sb.append("\n");
        }
        sb.append("\n## Ownership\n\n").append(spec.roleRule().description()).append("\n\n");
        sb.append("## AI tools\n\n| tool | risk | confirmation | routes on |\n|---|---|---|---|\n");
        for (ToolSpec t : spec.tools()) {
            sb.append("| `").append(t.name()).append("` | ").append(t.riskLevel())
                    .append(" | ").append(t.requiresConfirmation() ? "yes" : "no").append(" | ")
                    .append(String.join(" / ", t.triggers() == null ? List.of() : t.triggers()))
                    .append(" |\n");
        }
        sb.append("\n## Conversation\n\n");
        sb.append("`POST /ai/chat` takes a **message** and answers the conversation shape the reference\n");
        sb.append("implementation and the KeelBase4J runtime both answer — the same fields, so a client written\n");
        sb.append("against either works unchanged:\n\n");
        sb.append("```\nPOST /ai/chat  { message, conversationId?, customerId? }\n");
        sb.append("->  { conversationId, reply, provider, model, toolCalls? }\n");
        sb.append("```\n\n");
        sb.append("That is the frozen `chat-response` and nothing more. A tool call's result is not a field\n");
        sb.append("of it: it is reported as events on `POST /ai/chat/stream`, which is also where a write's\n");
        sb.append("confirmation token travels — a non-streaming call returns none.\n\n");
        sb.append("A turn runs planner -> engine -> replier. The planner proposes a tool, the engine decides\n");
        sb.append("whether it may run (a write waits on a human confirmation, and nothing is written until it\n");
        sb.append("is approved), and the replier says what happened. `conversationId` names rows in\n");
        sb.append("`conversation_messages` — a transcript of the turns and nothing more: no embeddings, no\n");
        sb.append("retrieval, no memory policy.\n\n");
        sb.append("**The answer is not from a model.** With none configured the reply is deterministic:\n");
        sb.append("`provider` is `deterministic` and `model` is `none`, and the text only describes what the\n");
        sb.append("engine actually did. Two seams make that replaceable — `ToolCallPlanner` (what to call) and\n");
        sb.append("`ChatReplier` (what to say). Declare your own bean for either and the default steps aside;\n");
        sb.append("`ChatPipelineConfiguration` registers both behind `@ConditionalOnMissingBean`.\n\n");
        sb.append("The default planner routes a message to a tool by the words in the \"routes on\" column\n");
        sb.append("above — the words the request itself used. That routing is a **fallback for running without\n");
        sb.append("a model, not a classifier and not \"AI\"**: a deployment that puts a model behind the planner\n");
        sb.append("never reads those words.\n\n");
        sb.append("## Side effects\n\n");
        sb.append("Every write the tools perform is recorded, and every one of those records is revocable:\n\n");
        sb.append("```\nGET    /ai/tool-effects?page=1&limit=20\n");
        sb.append("->  { total, page, limit, items: [ { id, toolName, conversationId, resultType, resultId,\n");
        sb.append("      argsHash, createdAt, targetExists, targetSoftDeleted, targetTitle,\n");
        sb.append("      revokeClass, revokeStatus, status, revocable } ] }\n");
        sb.append("DELETE /ai/tool-effects/{id}   ->  { effectId, resultType, revokeClass, revokeStatus, revoked }\n```\n\n");
        sb.append("The envelope and the item's fields are the shape the runtime-neutral console reads — the\n");
        sb.append("same one the KeelBase4J runtime answers, so the console pages and renders both. `limit` is\n");
        sb.append("capped at 100; a non-manager sees their own effects whatever id they pass, and a manager\n");
        sb.append("may narrow the list by `userId`.\n\n");
        sb.append("`targetExists` / `targetSoftDeleted` / `targetTitle` describe the row the write created,\n");
        sb.append("looked up at read time (its note is the title the console shows). `revokeClass` is\n");
        sb.append("`local_compensate` because every write here is a local row and revoking one really does\n");
        sb.append("compensate: the target is soft-deleted. `conversationId` names the conversation that\n");
        sb.append("proposed the write: it is carried from the proposal to the decision on the pending record,\n");
        sb.append("so an approval arriving in a request of its own still knows one — null only when the\n");
        sb.append("proposal named no conversation (a task run).\n\n");
        sb.append("## The operations audit\n\n");
        sb.append("A **second chain** beside the AI one, answering a different question: the AI chain records\n");
        sb.append("what a tool call came to, and this records the business writes themselves — including the\n");
        sb.append("ones no AI took part in. It is a table, so unlike the AI chain it survives a restart.\n\n");
        sb.append("```\nGET /audit/operations/logs?page=1&limit=20   (administrators only)\n");
        sb.append("->  { total, page, limit, items: [ { id, userId, action, method, path, targetId, ip,\n");
        sb.append("      userAgent, statusCode, createdAt } ] }\n```\n\n");
        sb.append("What is recorded is decided by what a request *is* — a write method on a business path — not\n");
        sb.append("by who sent it. A request that carried no identity (a refused login attempt) is recorded with\n");
        sb.append("nobody attached rather than dropped. The AI surface is excluded, because its calls have the\n");
        sb.append("chain next door. The row is written after the handler has answered, so it can say how the\n");
        sb.append("request ended, and failing to write it never fails the request.\n\n");
        sb.append("What is deliberately **not** recorded: the request body (there is no PII declaration to\n");
        sb.append("redact by, so a stored body would be a leak), the field-level diff, and the two hash columns\n");
        sb.append("(a chained audit needs a second chain head and its own locking). The recorder is on by\n");
        sb.append("default; turn it off with `keelbase.operations-audit.enabled=false`.\n\n");
        sb.append("## Streaming\n\n");
        sb.append("`POST /ai/chat/stream` answers `text/event-stream` — the channel the web console's assistant\n");
        sb.append("drawer uses. An administrator is sent to `/admin/ai/chat/stream`: same handler, gated by the\n");
        sb.append("role. It runs the same turn as `POST /ai/chat`; only the reporting differs:\n\n");
        sb.append("```\ntool_start -> text -> confirmation_request -> (wait) -> confirmation_decision");
        sb.append(" -> tool_end -> done\n```\n\n");
        sb.append("**Why it stays open.** The card that asks for a confirmation is built from the\n");
        sb.append("`confirmation_request` event, and the token in it is the only way a client learns what to\n");
        sb.append("approve. The stream then *waits*: a write approved while it is open — from this client or from\n");
        sb.append("another surface — comes back as `confirmation_decision` on the same connection. A stream that\n");
        sb.append("closed with the turn could not deliver that.\n\n");
        sb.append("**Its boundaries, stated.** The wait is the contract's confirmation window\n");
        sb.append("(`keelbase.chat.stream-wait-ms` shortens it; zero means the contract's). When the wait runs\n");
        sb.append("out, the stream closes with `done` and **no** decision — nothing here expires a confirmation,\n");
        sb.append("so the write is still yours to approve out of band, and the stream does not pretend\n");
        sb.append("otherwise. The registry behind all this is **in-process**: a second instance cannot reach a\n");
        sb.append("stream opened here, and there is **no replay** — a stream serves the view that is open now,\n");
        sb.append("not a durable log.\n\n");
        sb.append("## Identity\n\n");
        sb.append("Callers prove who they are with a KeelBase delegation token — `Authorization: Bearer <jwt>` —\n");
        sb.append("verified against the frozen contract with `keelbase.delegation.secret` and\n");
        sb.append("`keelbase.delegation.audience` (set `DELEGATION_SECRET` / `DELEGATION_AUDIENCE`, or edit\n");
        sb.append("`application.properties`). Nothing runs anonymously on the governance surface: a request\n");
        sb.append("without a valid token is a 401, and the surfaces the runtime keeps for an admin — the tool\n");
        sb.append("catalogue and the chain's own state — are closed to everyone else. Two kinds of request ask\n");
        sb.append("for no token at all: the login page's own courtesy calls (`POST /auth/login`, where this\n");
        sb.append("deployment turns it on; the visit ping; and the provider list it reads before anyone has\n");
        sb.append("signed in), and the `/app/*` self-description endpoints, which name this deployment rather\n");
        sb.append("than any caller and are what a readiness probe should ask.\n\n");
        sb.append("`POST /auth/login` and `GET /auth/me` are the console's session point — who the caller is,\n");
        sb.append("settled before what they may do. Login is **off unless you set `KEELBASE_DEMO_PASSWORD`**\n");
        sb.append("(no default; nothing is written into the source): with a passphrase set, one of the declared\n");
        sb.append("demo identities can sign in by name, and the session it mints is the same delegation token\n");
        sb.append("this application already verifies — no second token format, and no refresh token.\n");
        sb.append("`GET /auth/me` answers `{username, role}`, which is what the console keys its shell off. Both\n");
        sb.append("are demo furniture: one shared passphrase across the declared identities, no rate limit of its\n");
        sb.append("own, and no registration, password reset or SSO — replace the identity source for real users.\n\n");
        sb.append("`LocalIdentities` says which local user and role a *verified* subject holds. It ships with the\n");
        sb.append("demo entries; **replace them with your directory**. A role is never taken from the request —\n");
        sb.append("a caller that could state its own role would grant itself one.\n\n");
        sb.append("`HeaderIdentityResolver` ships for deployments that sit behind a guard which has already\n");
        sb.append("authenticated the caller; declare it as your `IdentityResolver` bean in that shape, or\n");
        sb.append("implement the `IdentityResolver` interface for anything else.\n");
        sb.append("\n## Run\n\n```\nmvn spring-boot:run\n```\n");
        sb.append("\n## Operations\n\n");
        sb.append("**What survives a restart, and what does not — read this before a pilot.** The business\n");
        sb.append("tables are durable: `customers`, `follow_ups`, `conversation_messages` and the operations\n");
        sb.append("audit live in the database, and `flyway_schema_history` records which migrations built it.\n");
        sb.append("**The AI governance record does not: the AI audit chain, the pending confirmations and the\n");
        sb.append("side-effect records — which is where \"revoked\" is remembered — are held in memory** (the\n");
        sb.append("operations chain has a section of its own below and is the exception among the records: its\n");
        sb.append("rows are a table), so restarting the process\n");
        sb.append("loses them. A verification you ran before a restart cannot be repeated afterwards, a\n");
        sb.append("confirmation nobody answered goes with the process, and a revocation recorded before a\n");
        sb.append("restart is no longer listed. The KeelBase4J runtime — the embedding host, not this\n");
        sb.append("application — keeps those in its own tables; this generated application does not. Plan\n");
        sb.append("restarts accordingly, and tell whoever relies on the record. That half is\n");
        sb.append("**demo-grade on purpose**, not production-grade governance record-keeping — it is a\n");
        sb.append("deliberate simplification rather than an oversight, and moving that state into its\n");
        sb.append("own tables is the change to make before a deployment relies on it.\n\n");
        sb.append("### Where the state lives\n\n");
        sb.append("| state | store | survives a restart |\n|---|---|---|\n");
        sb.append("| `customers` · `follow_ups` · `conversation_messages` · `operation_audit_logs` | database | yes |\n");
        sb.append("| `flyway_schema_history` | database | yes |\n");
        sb.append("| the **AI** audit chain · pending confirmations · side effects and revocations | process memory | **no** |\n\n");
        sb.append("By default the database is an H2 file under `./data/`. For anything but a demo, point it at\n");
        sb.append("PostgreSQL — the driver and the Flyway PostgreSQL module are already on the classpath, and the\n");
        sb.append("URL comes from Spring's usual keys, so no source change is needed:\n\n");
        sb.append("```\nSPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/<database> \\\n");
        sb.append("SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=... \\\n");
        sb.append("java -jar target/<module>-<version>.jar\n```\n\n");
        sb.append("These defaults are **spike values, not deployment ones** — set them:\n\n");
        sb.append("| key | what it is |\n|---|---|\n");
        sb.append("| `DELEGATION_SECRET` · `DELEGATION_AUDIENCE` | the delegation token this application verifies |\n");
        sb.append("| `keelbase.audit.hmac-key` | the HMAC key the audit chain is hashed with |\n");
        sb.append("| `KEELBASE_DEMO_PASSWORD` | turns the login surface on; leave it unset if you replace `LocalIdentities` |\n\n");
        sb.append("### Backup\n\n");
        sb.append("The durable state is the database, so a dump of it is a complete backup:\n\n");
        sb.append("```\npg_dump -Fc <database> > backup-$(date +%F).dump     # PostgreSQL\n");
        sb.append("cp data/<module>.mv.db backup-<date>.mv.db           # the H2 default\n```\n\n");
        sb.append("Nothing else needs saving: the application keeps no other durable state and writes no files\n");
        sb.append("of its own beyond the H2 database if you keep the default.\n\n");
        sb.append("### Upgrade\n\n");
        sb.append("Flyway owns the schema and `ddl-auto=validate` re-checks the entities against it at every\n");
        sb.append("boot, so an upgrade is a migration and a mismatch stops the application instead of\n");
        sb.append("half-working.\n\n");
        sb.append("1. **Back up first** (above) — migrations change the schema in place.\n");
        sb.append("2. Add an **additive** migration as\n");
        sb.append("   `src/main/resources/db/migration/V<next>__<what it does>.sql` — `ALTER TABLE ... ADD\n");
        sb.append("   COLUMN`, not a rewrite; the rows already there are the point of the exercise.\n");
        sb.append("3. Start the application. Flyway applies what has not run yet, then Hibernate validates the\n");
        sb.append("   result: a column an entity declares but no migration added fails the boot, loudly.\n");
        sb.append("4. To roll back, restore the dump and deploy the previous artifact — the code and the schema\n");
        sb.append("   move together, which is why step 1 is not optional.\n\n");
        sb.append("The migrations are ordinary readable SQL, and the deployed application's own\n");
        sb.append("`flyway_schema_history` is the record of where it actually is.\n");
        sb.append("\n## Regenerating\n\n");
        sb.append("Edit this code freely, anywhere — the marker block is only a suggestion of where it\n");
        sb.append("is least likely to collide with a generated change. `.keelbase/baseline/` records what\n");
        sb.append("the generator produced last time and is what lets a regeneration merge your edits\n");
        sb.append("instead of overwriting them, so **keep it in version control**. A region both sides\n");
        sb.append("changed is left with `diff3` conflict markers rather than resolved by a guess.\n");
        return sb.toString();
    }

    private String properties(String module) {
        return """
                spring.application.name=%s
                # File-backed on purpose: a change has to carry the rows already in the database, and
                # an in-memory one makes that promise untestable (the data dies with the process).
                # WRITE_DELAY=0 flushes every commit immediately. H2's default write delay means a
                # process killed soon after a write can lose it, which would leave "the data
                # survives" true only most of the time.
                spring.datasource.url=jdbc:h2:file:./data/%s;WRITE_DELAY=0
                # Flyway owns the schema. Hibernate only checks the entities against what Flyway
                # built; "update" would mutate the schema behind Flyway's back and hide drift.
                spring.jpa.hibernate.ddl-auto=validate
                # The whole surface is mounted under the reference's path prefix, because the
                # runtime-neutral frontend keeps one base URL (/api/v1) and does not branch on which
                # runtime it is talking to. An app answering at the root is not one it can talk to.
                server.servlet.context-path=/api/v1
                # The delegation token this application verifies (the frozen contract's §3). Spike
                # defaults so the demos run; a deployment supplies DELEGATION_SECRET and
                # DELEGATION_AUDIENCE. Same keys as the runtime's, so one token works on both.
                keelbase.delegation.secret=${DELEGATION_SECRET:cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}
                keelbase.delegation.audience=${DELEGATION_AUDIENCE:keelbase4j}
                # The passphrase that turns on this application's login surface (POST /auth/login), read
                # from the environment (KEELBASE_DEMO_PASSWORD binds here). Deliberately left with no
                # value: an application that shipped one would be born with a login nobody asked for, so
                # while this is empty every login attempt is refused. The demo script injects it; a
                # deployment with real users replaces the identity source instead (identity/IdentityResolver).
                #
                # 打开本应用登录面（POST /auth/login）的口令，从环境读入（KEELBASE_DEMO_PASSWORD 绑到这里）。
                # 刻意<b>不给值</b>：一个自带口令的应用等于一出生就带着一个没人要的登录，所以只要这里是空的，
                # 每一次登录尝试都会被拒绝。演示脚本负责注入；有真实用户的部署应改为替换身份源
                # （identity/IdentityResolver）。
                keelbase.demo.password=${KEELBASE_DEMO_PASSWORD:}
                """.formatted(module, module);
    }

    // ── schema migrations ───────────────────────────────────────────────────────

    /**
     * One column of one table. {@code definition} is what follows the column name — for {@code id} it
     * carries the key clause too, because the baseline needs it; {@code id} is never added by a
     * migration, so the diff never emits that form.
     */
    private record Column(String name, String definition) {
    }

    /**
     * The columns of one entity's table — the single source both the baseline and the diff are built
     * from, so the schema Flyway creates and the schema a change evolves to cannot disagree.
     */
    private static List<Column> columns(EntitySpec entity) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("id", "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"));
        for (FieldSpec f : entity.fields()) {
            columns.add(new Column(snake(f.name()), sqlType(javaType(f.type()))));
        }
        columns.add(new Column("owner_user_id", "VARCHAR(255)"));
        // The zone is the point: `deleted_at` is an `Instant`, and Hibernate maps that to
        // `timestamp(6) with time zone`. A bare `TIMESTAMP` still passes `validate` — it then rounds
        // every instant through the JVM's zone on the way in and out.
        //
        // 时区是关键：`deleted_at` 是 `Instant`，Hibernate 把它映射成 `timestamp(6) with time zone`。
        // 裸 `TIMESTAMP` 照样能过 `validate` —— 它只是会把每个时刻按 JVM 时区在进出时换算一遍。
        columns.add(new Column("deleted_at", "TIMESTAMP(6) WITH TIME ZONE"));
        return columns;
    }

    /** The SQL type Hibernate expects for a mapped Java type; {@code ddl-auto=validate} checks it. */
    private static String sqlType(String javaType) {
        return switch (javaType) {
            case "Long" -> "BIGINT";
            case "Boolean" -> "BOOLEAN";
            default -> "VARCHAR(255)";
        };
    }

    private String migrationBaseline(BusinessSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Baseline schema for the ").append(spec.module()).append(" module, generated from the\n");
        sb.append("-- business specification. Flyway records the checksum of each applied migration, so an\n");
        sb.append("-- applied migration must never be edited — a change is a new version instead.\n");
        for (EntitySpec entity : spec.entities()) {
            List<Column> columns = columns(entity);
            sb.append("\nCREATE TABLE ").append(table(entity.name())).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                sb.append("    ").append(columns.get(i).name()).append(' ')
                        .append(columns.get(i).definition()).append(i == columns.size() - 1 ? "\n" : ",\n");
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    private static String migrationAlter(Map<String, List<String>> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Schema change generated from the business specification. Additive only: the columns\n");
        sb.append("-- and rows already in the database are left alone, which is what carries the data over.\n");
        missing.forEach((table, definitions) -> {
            for (String definition : definitions) {
                sb.append("\nALTER TABLE ").append(table).append(" ADD COLUMN ").append(definition).append(";\n");
            }
        });
        return sb.toString();
    }

    /** The columns the spec wants that the applied migrations do not create yet, per table. */
    private static Map<String, List<String>> missingColumns(BusinessSpec spec, Map<String, Set<String>> applied) {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (EntitySpec entity : spec.entities()) {
            String table = table(entity.name());
            Set<String> present = applied.getOrDefault(table, Set.of());
            List<String> absent = new ArrayList<>();
            for (Column column : columns(entity)) {
                if (!present.contains(column.name())) {
                    absent.add(column.name() + " " + column.definition());
                }
            }
            if (!absent.isEmpty()) {
                missing.put(table, absent);
            }
        }
        return missing;
    }

    private static String describe(Map<String, List<String>> missing) {
        StringBuilder sb = new StringBuilder("add");
        missing.forEach((table, definitions) -> definitions.forEach(
                definition -> sb.append('_').append(table).append('_').append(definition.split("\\s+")[0])));
        return sb.toString();
    }

    /**
     * Read back which columns each table already has, from the migrations already in the project. Only
     * the forms this generator emits are recognised — a hand-written migration it cannot parse simply
     * does not contribute to the diff, which errs toward leaving the developer's schema alone.
     */
    private static Map<String, Set<String>> appliedColumns(Path migrationDir) {
        Map<String, Set<String>> byTable = new LinkedHashMap<>();
        if (!Files.isDirectory(migrationDir)) {
            return byTable;
        }
        List<Path> migrations;
        try (var stream = Files.list(migrationDir)) {
            migrations = stream.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + migrationDir, e);
        }
        for (Path migration : migrations) {
            try {
                String table = null;
                for (String raw : Files.readAllLines(migration, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.regionMatches(true, 0, "CREATE TABLE ", 0, 13)) {
                        table = line.substring(13).split("[ (]")[0].toLowerCase(Locale.ROOT);
                        continue;
                    }
                    if (line.equals(");")) {
                        table = null;
                        continue;
                    }
                    if (table != null && !line.isEmpty()) {
                        byTable.computeIfAbsent(table, key -> new LinkedHashSet<>())
                                .add(line.split("[ ,(]")[0].toLowerCase(Locale.ROOT));
                        continue;
                    }
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 6 && "ALTER".equalsIgnoreCase(parts[0])
                            && "ADD".equalsIgnoreCase(parts[3]) && "COLUMN".equalsIgnoreCase(parts[4])) {
                        byTable.computeIfAbsent(parts[2].toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>())
                                .add(parts[5].toLowerCase(Locale.ROOT));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + migration, e);
            }
        }
        return byTable;
    }

    private static int highestVersion(Path migrationDir) {
        int highest = 0;
        try (var stream = Files.list(migrationDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                int marker = name.indexOf("__");
                if (!name.startsWith("V") || marker < 0) {
                    continue;
                }
                try {
                    highest = Math.max(highest, Integer.parseInt(name.substring(1, marker)));
                } catch (NumberFormatException notOurs) {
                    // Flyway rejects malformed versions on its own; do not guess here.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + migrationDir, e);
        }
        return highest;
    }

    /** Write only when absent: an applied migration is immutable, so regeneration must not rewrite it. */
    private static Path writeIfAbsent(Path path, String content) {
        return Files.exists(path) ? path : write(path, content);
    }

    private String application(String pkg) {
        return """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """.formatted(pkg);
    }

    // ── domain ──────────────────────────────────────────────────────────────────

    private String entityClass(String pkg, EntitySpec entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".domain;\n\n");
        sb.append("import jakarta.persistence.Column;\nimport jakarta.persistence.Entity;\n");
        sb.append("import jakarta.persistence.GeneratedValue;\nimport jakarta.persistence.GenerationType;\n");
        sb.append("import jakarta.persistence.Id;\nimport jakarta.persistence.Table;\n");
        sb.append("import java.time.Instant;\n\n");
        sb.append("@Entity\n@Table(name = \"").append(table(entity.name())).append("\")\n");
        sb.append("public class ").append(entity.name()).append(" {\n\n");
        sb.append("    @Id\n    @GeneratedValue(strategy = GenerationType.IDENTITY)\n    private Long id;\n\n");
        for (FieldSpec f : entity.fields()) {
            sb.append("    @Column(name = \"").append(snake(f.name())).append("\")\n");
            sb.append("    private ").append(javaType(f.type())).append(" ").append(f.name()).append(";\n\n");
        }
        sb.append("    @Column(name = \"owner_user_id\")\n    private String ownerUserId;\n\n");
        sb.append("    @Column(name = \"deleted_at\")\n    private Instant deletedAt;\n\n");
        sb.append("    public Long getId() {\n        return id;\n    }\n\n");
        for (FieldSpec f : entity.fields()) {
            sb.append("    public ").append(javaType(f.type())).append(" get").append(capitalize(f.name()))
                    .append("() {\n        return ").append(f.name()).append(";\n    }\n\n");
            sb.append("    public void set").append(capitalize(f.name())).append("(")
                    .append(javaType(f.type())).append(" ").append(f.name()).append(") {\n        this.")
                    .append(f.name()).append(" = ").append(f.name()).append(";\n    }\n\n");
        }
        sb.append("    public String getOwnerUserId() {\n        return ownerUserId;\n    }\n\n");
        sb.append("    public void setOwnerUserId(String ownerUserId) {\n        this.ownerUserId = ownerUserId;\n    }\n\n");
        sb.append("    public Instant getDeletedAt() {\n        return deletedAt;\n    }\n\n");
        sb.append("    public void setDeletedAt(Instant deletedAt) {\n        this.deletedAt = deletedAt;\n    }\n\n");
        sb.append(userCodeBlock());
        sb.append("}\n");
        return sb.toString();
    }

    // ── conversation (the transcript behind conversationId) ─────────────────────────────────────

    /**
     * The minimal conversation record ADR-0013 D4 asks for, matching the runtime's own.
     *
     * <p>It exists so that {@code conversationId} names something real. It is a <b>transcript</b>: turns and
     * nothing more — no embeddings, no retrieval, no memory policy (ADR-0009 D3's boundary).
     */
    private String conversationMessageEntity(String pkg) {
        return """
                package %s.conversation;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.GeneratedValue;
                import jakarta.persistence.GenerationType;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import java.time.Instant;

                /** One turn of a conversation: who said it, what they said, and when. */
                @Entity
                @Table(name = "conversation_messages")
                public class ConversationMessage {

                    public static final String USER = "user";
                    public static final String ASSISTANT = "assistant";

                    @Id
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;

                    @Column(name = "conversation_id", nullable = false)
                    private String conversationId;

                    @Column(name = "user_id", nullable = false)
                    private String userId;

                    @Column(nullable = false)
                    private String role;

                    @Column(nullable = false, length = 4000)
                    private String content;

                    @Column(name = "created_at", nullable = false)
                    private Instant createdAt = Instant.now();

                    protected ConversationMessage() {
                    }

                    public ConversationMessage(String conversationId, String userId, String role, String content) {
                        this.conversationId = conversationId;
                        this.userId = userId;
                        this.role = role;
                        this.content = content;
                    }

                    public Long getId() {
                        return id;
                    }

                    public String getConversationId() {
                        return conversationId;
                    }

                    public String getUserId() {
                        return userId;
                    }

                    public String getRole() {
                        return role;
                    }

                    public String getContent() {
                        return content;
                    }

                    public Instant getCreatedAt() {
                        return createdAt;
                    }
                }
                """.formatted(pkg);
    }

    private String conversationMessageRepository(String pkg) {
        return """
                package %s.conversation;

                import java.util.List;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.repository.query.Param;

                public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

                    /** A conversation's turns, oldest first — uncapped, because a trace is of the whole of it. */
                    List<ConversationMessage> findByConversationIdOrderByIdAsc(String conversationId);

                    /**
                     * The conversations a caller has — their ids, and nothing else. There is no
                     * conversation table, only its messages, so a conversation is whatever a set of
                     * messages shares: the id is the whole record of its existence, and everything else
                     * about it is derived. The same reason means there is nothing here to page — a
                     * caller's own conversations are the bound.
                     *
                     * 某个调用方有哪些会话 —— 只有会话 id。**没有会话表**、只有它的消息，所以一个会话就是
                     * 「一批消息共用的那个 id」：id 就是它存在的全部记录，其余一切都由消息推出。同一个原因也说明
                     * 这里没有可分页的东西 —— **调用方自己的会话**就是界限。
                     */
                    @Query("select distinct m.conversationId from ConversationMessage m where m.userId = :userId")
                    List<String> conversationIdsFor(@Param("userId") String userId);

                    /** A conversation's opening turns, oldest first — the preview a list shows. */
                    List<ConversationMessage> findTop2ByConversationIdOrderByIdAsc(String conversationId);
                }
                """.formatted(pkg);
    }

    /**
     * The conversation a turn belongs to.
     *
     * <p>Two rules, both about the id meaning something: a conversation is opened per caller, and an id this
     * application never issued is <b>not adopted</b> — a caller cannot reach into somebody else's
     * conversation by naming it. A conversation that does not exist yet starts one.
     */
    private String conversationStore(String pkg) {
        return """
                package %s.conversation;

                import java.util.List;
                import java.util.Optional;
                import java.util.UUID;
                import org.springframework.stereotype.Component;

                /**
                 * The conversation a turn belongs to: opens one when the caller has none, records turns, and
                 * hands back the history a replier may read.
                 */
                @Component
                public class ConversationStore {

                    private final ConversationMessageRepository repository;

                    public ConversationStore(ConversationMessageRepository repository) {
                        this.repository = repository;
                    }

                    /**
                     * The id to continue, or a new one. An id this application never issued starts a new
                     * conversation rather than being adopted.
                     */
                    public String openFor(String conversationId, String userId) {
                        if (conversationId != null && !conversationId.isBlank()
                                && ownedBy(conversationId, userId)) {
                            return conversationId;
                        }
                        return UUID.randomUUID().toString();
                    }

                    public ConversationMessage append(String conversationId, String userId, String role,
                                                      String content) {
                        return repository.save(new ConversationMessage(conversationId, userId, role, content));
                    }

                    /** The turns of a conversation, oldest first. */
                    public List<ConversationMessage> history(String conversationId) {
                        return repository.findByConversationIdOrderByIdAsc(conversationId);
                    }

                    private boolean ownedBy(String conversationId, String userId) {
                        Optional<ConversationMessage> first = history(conversationId).stream().findFirst();
                        return first.isPresent() && first.get().getUserId().equals(userId);
                    }
                }
                """.formatted(pkg);
    }

    private String conversationMigration() {
        return """
                -- The conversation transcript (ADR-0013 D4). A table of turns and nothing more: it exists so
                -- that the conversationId a chat response carries names something real. No embeddings, no
                -- retrieval, no memory policy — see the module README.
                CREATE TABLE conversation_messages (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    conversation_id VARCHAR(255) NOT NULL,
                    user_id VARCHAR(255) NOT NULL,
                    role VARCHAR(255) NOT NULL,
                    content VARCHAR(4000) NOT NULL,
                    -- `created_at` is an `Instant`; the zone is part of the type. A bare `TIMESTAMP` passes
                    -- Hibernate's schema check and then rounds through the JVM's zone.
                    -- `created_at` 是 `Instant`，时区是类型的一部分。裸 `TIMESTAMP` 能过 Hibernate 的
                    -- schema 校验，然后按 JVM 时区换算一遍。
                    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
                );

                CREATE INDEX idx_conversation_messages_conversation ON conversation_messages (conversation_id);
                """;
    }

    private String repository(String pkg, EntitySpec entity) {
        return """
                package %s.domain;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface %sRepository extends JpaRepository<%s, Long> {
                }
                """.formatted(pkg, entity.name(), entity.name());
    }

    // ── governance wiring (self-contained) ──────────────────────────────────────

    private String aiToolInterface(String pkg) {
        return """
                package %s.ai;

                import java.util.Map;

                /** A governable AI tool. Server-side governance metadata never reaches a model. */
                public interface AiTool {
                    String name();

                    /**
                     * What the tool does, as the business specification declared it. It is the one
                     * description a reader of {@code GET /ai/tools} is shown, so it says what the tool
                     * is for and nothing about how the call is governed — the risk level is where that
                     * belongs (the runtime's hard rule 7, applied here).
                     *
                     * 这个工具做什么，按业务规格所声明的。它是 `GET /ai/tools` 的读者所看到的**唯一一条**描述，
                     * 故它说这个工具**是干什么用的**、不说这次调用**怎么被治理** —— 治理结论属于风险级
                     * （本运行时的硬规则 7，用在这里）。
                     */
                    String description();

                    String riskLevel();

                    /**
                     * What this tool writes, as the business specification declared it ("follow_up"), or
                     * {@code null} for a tool that writes nothing. It names the <em>result</em>, not the
                     * tool: the console groups recorded effects by what they produced.
                     */
                    String resultType();

                    Map<String, Object> execute(Map<String, Object> args, String userId);
                }
                """.formatted(pkg);
    }

    private String governanceGate(String pkg) {
        return """
                package %s.ai;

                import cn.com.keelbase.protocol.RiskLevel;
                import org.springframework.stereotype.Component;

                /** A tool's declared risk level maps to a decision via the frozen protocol binding. */
                @Component
                public class GovernanceGate {

                    public String decide(AiTool tool) {
                        String strategy = RiskLevel.strategy(tool.riskLevel());
                        switch (strategy) {
                            case "block":
                                return "BLOCK";
                            case "human_approval":
                                return "REQUIRE_APPROVAL";
                            case "confirmation":
                                return "CONFIRM";
                            default:
                                return "ALLOW";
                        }
                    }
                }
                """.formatted(pkg);
    }

    private String toolRegistry(String pkg) {
        return """
                package %s.ai;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.stereotype.Component;

                @Component
                public class ToolRegistry {

                    private final Map<String, AiTool> tools = new LinkedHashMap<>();

                    public ToolRegistry(List<AiTool> beans) {
                        for (AiTool tool : beans) {
                            tools.put(tool.name(), tool);
                        }
                    }

                    public AiTool require(String name) {
                        AiTool tool = tools.get(name);
                        if (tool == null) {
                            throw new IllegalArgumentException("unknown tool: " + name);
                        }
                        return tool;
                    }

                    public List<AiTool> all() {
                        return List.copyOf(tools.values());
                    }
                }
                """.formatted(pkg);
    }

    private String confirmationStore(String pkg) {
        return """
                package %s.ai;

                import cn.com.keelbase.protocol.CanonicalJson;
                import cn.com.keelbase.protocol.Json;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.UUID;
                import java.util.concurrent.ConcurrentHashMap;
                import org.springframework.http.HttpStatus;
                import org.springframework.stereotype.Component;
                import org.springframework.web.server.ResponseStatusException;

                /** Pending confirmations: a write does not happen until a token is approved. */
                @Component
                public class ConfirmationStore {

                    /**
                     * A pending write. {@code conversationId} is carried from the proposal to the
                     * decision so the effect the approval finally produces can name the conversation
                     * that proposed it — the decision arrives in a request of its own and would
                     * otherwise not know one. Null when the proposal came from no conversation.
                     *
                     * 一次待决的写。{@code conversationId} 从**提议时刻**被带到**裁决时刻**，好让批准最终产出的那个
                     * 副作用说得清是**哪条会话**提议的 —— 裁决自己在一个请求里到达，否则它无从知道。提议不来自任何
                     * 会话时为 null。
                     */
                    public record Pending(String token, String toolName, String argsJson, String userId,
                                          String riskLevel, String conversationId) {
                    }

                    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

                    public String create(String userId, String toolName, Map<String, Object> args,
                                         String riskLevel, String conversationId) {
                        String token = UUID.randomUUID().toString();
                        pending.put(token, new Pending(token, toolName, CanonicalJson.json(args), userId,
                                riskLevel, conversationId));
                        return token;
                    }

                    /**
                     * Take the token out of pending, atomically. The caller that removes it is the only
                     * one that may act on it — {@code ConcurrentHashMap.remove(key, value)} is the whole
                     * arbitration, so a token cannot be approved twice however close together the two
                     * approvals arrive. Reading the token, acting, and removing it afterwards would let
                     * both callers past the check and run the tool twice.
                     */
                    public Pending claim(String token, String userId) {
                        Pending p = pending.get(token);
                        if (p == null) {
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown confirmation token");
                        }
                        if (!p.userId().equals(userId)) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "confirmation belongs to another operator");
                        }
                        if (!pending.remove(token, p)) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "confirmation already resolved");
                        }
                        return p;
                    }

                    @SuppressWarnings("unchecked")
                    public Map<String, Object> args(Pending p) {
                        return new LinkedHashMap<>((Map<String, Object>) Json.parse(p.argsJson()));
                    }
                }
                """.formatted(pkg);
    }

    /**
     * Who is waiting to hear how a confirmation was decided (ADR-0014 D2, mirroring ADR-0010 D2).
     *
     * <p>This is the one piece of server-side connection state this application has: a decision arrives
     * on one request and has to reach a stream opened by another. It holds callbacks rather than a
     * stream, so that whoever owns the stream decides how to finish it — a decision ends a stream
     * differently from a wait that simply expired.
     */
    private String confirmationWatchers(String pkg) {
        return """
                package %s.ai;

                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.function.Consumer;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                /**
                 * Who is waiting to hear how a confirmation was decided.
                 *
                 * <p>A decision arrives on one request and has to reach a stream opened by another, so this
                 * application needs somewhere to keep the connection between them. That is all this is: a
                 * token, and whatever asked to be told about it.
                 *
                 * <p><b>In-process, and it does not survive a second instance.</b> A stream opened against
                 * one instance is invisible to the other, so a decision taken there reaches nobody. The
                 * same limitation the audit chain's in-process store has: one instance, or a shared channel
                 * this application does not have. Stated rather than assumed away.
                 */
                @Component
                public class ConfirmationWatchers {

                    private static final Logger log = LoggerFactory.getLogger(ConfirmationWatchers.class);

                    /** What a stream asked to be told about: decided, or waited out. */
                    private record Waiter(Consumer<Map<String, Object>> onDecision, Runnable onExpiry) {
                    }

                    private final Map<String, Waiter> waiting = new ConcurrentHashMap<>();

                    /** Ask to be told when this confirmation is decided, or when its wait runs out. */
                    public void watch(String token, Consumer<Map<String, Object>> onDecision, Runnable onExpiry) {
                        waiting.put(token, new Waiter(onDecision, onExpiry));
                    }

                    /** Stop listening — the stream ended or its caller went away. Idempotent. */
                    public void stop(String token) {
                        waiting.remove(token);
                    }

                    /**
                     * Tell whoever is waiting what was decided.
                     *
                     * @return whether anyone was waiting. {@code false} is not an error: a confirmation
                     *         decided with no stream open is an ordinary outcome, and the decision stands
                     *         regardless of who heard about it.
                     */
                    public boolean decided(String token, Map<String, Object> decision) {
                        return deliver(token, waiter -> waiter.onDecision().accept(decision));
                    }

                    /**
                     * The wait ran out with nothing decided — the stream should stop waiting and close on
                     * its own terms rather than be cut off by a connection deadline.
                     *
                     * <p>This decides nothing. Nothing in this application expires a confirmation, so the
                     * write is still the operator's to approve out of band.
                     *
                     * @return whether anyone was still waiting, by the same reckoning as {@link #decided}.
                     */
                    public boolean expired(String token) {
                        // Not `Waiter::onExpiry`: as a Consumer that reference reads the Runnable and discards
                        // it — a return value adapted to void is dropped, not invoked. The run() is explicit.
                        return deliver(token, waiter -> waiter.onExpiry().run());
                    }

                    /**
                     * Hand the token's waiter to {@code to}, once, and forget it.
                     *
                     * <p>Removing before delivering is what makes a decision and an expiry mutually
                     * exclusive: both come through here, so whichever removes the entry delivers and the
                     * other finds nothing.
                     */
                    private boolean deliver(String token, Consumer<Waiter> to) {
                        Waiter waiter = waiting.remove(token);
                        if (waiter == null) {
                            return false;
                        }
                        try {
                            to.accept(waiter);
                        } catch (RuntimeException brokenStream) {
                            // Delivering the news is best effort: a decision is already recorded and stands,
                            // and an expiry was never a fact to lose. Letting this escape would turn a
                            // successful approval into a failed request because a browser tab went away.
                            log.warn("could not deliver the news about a confirmation to its stream", brokenStream);
                        }
                        return true;
                    }
                }
                """.formatted(pkg);
    }

    private String sideEffectStore(String pkg) {
        return """
                package %s.ai;

                import java.nio.charset.StandardCharsets;
                import java.security.MessageDigest;
                import java.time.Instant;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicLong;
                import org.springframework.stereotype.Component;

                /**
                 * Records AI write side effects so they can be revoked (local compensation).
                 *
                 * <p>The record carries what the console's own model requires of it — which tool ran,
                 * what it produced, a hash of the arguments, when, and the revoke facts — so the effect
                 * list is a row the console can render rather than a bare array of ids.
                 *
                 * <p><b>What it produced is captured, not looked up.</b> The frozen {@code traceItem}
                 * calls it {@code afterSnapshot}, and it is stored for the reason the name gives: the row
                 * as it stands today is not the row the decision produced. There is no {@code
                 * beforeSnapshot} beside it — nothing in this application overwrites an existing row, so
                 * there is no before to record.
                 *
                 * <p><b>它产出了什么，是捕获下来的、不是回查出来的。</b> 冻结的 {@code traceItem} 管它叫
                 * {@code afterSnapshot}，而它**被存下来**的理由就在这个名字里：**今天这一行**的样子**不是这次
                 * 决策产出的样子**。旁边没有 {@code beforeSnapshot} —— 本应用**不改写既有行**，所以没有「之前」可记。
                 */
                @Component
                public class SideEffectStore {

                    public record Effect(Long id, String userId, String toolName, String resultType,
                                         Long resultId, String revokeClass, String revokeStatus,
                                         String argsHash, String afterSnapshot, Instant createdAt,
                                         String conversationId) {
                    }

                    private final AtomicLong seq = new AtomicLong();
                    private final List<Effect> effects = new ArrayList<>();

                    public synchronized Effect record(String userId, String toolName, String resultType,
                                                      Long resultId, Map<String, Object> args,
                                                      String afterSnapshot, String conversationId) {
                        // Every write this application performs is on its own row, so the honest class is
                        // local compensation — and the revoke path does perform it: the controller
                        // soft-deletes the target. Never `governed_external` here: nothing outside this
                        // application is being compensated, and claiming a compensation channel that does
                        // not exist is how a console ends up offering a button that cannot work.
                        Effect e = new Effect(seq.incrementAndGet(), userId, toolName, resultType, resultId,
                                "local_compensate", "executed", argsHash(args), afterSnapshot, Instant.now(),
                                conversationId);
                        effects.add(e);
                        return e;
                    }

                    /**
                     * The effects this conversation's writes produced, oldest first — the last half of a
                     * trace. An effect from a call with no conversation (a task run) is left out.
                     *
                     * 这条会话的写产出的副作用，最早的在前 —— 一条追踪的最后一半。**没有会话**的调用产出的
                     * 副作用不出现。
                     */
                    public synchronized List<Effect> byConversation(String conversationId) {
                        return effects.stream()
                                .filter(e -> conversationId.equals(e.conversationId()))
                                .toList();
                    }

                    public synchronized Effect requireOwned(Long id, String userId, boolean manager) {
                        Effect e = effects.stream().filter(x -> x.id().equals(id)).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("unknown side effect"));
                        if (!manager && !e.userId().equals(userId)) {
                            throw new IllegalStateException("not your side effect");
                        }
                        return e;
                    }

                    public synchronized void setRevoked(Long id) {
                        for (int i = 0; i < effects.size(); i++) {
                            Effect e = effects.get(i);
                            if (e.id().equals(id)) {
                                effects.set(i, new Effect(e.id(), e.userId(), e.toolName(), e.resultType(),
                                        e.resultId(), e.revokeClass(), "revoked", e.argsHash(),
                                        e.afterSnapshot(), e.createdAt(), e.conversationId()));
                            }
                        }
                    }

                    /** Every effect, oldest first. Narrowing and paging it is the caller's business. */
                    public synchronized List<Effect> all() {
                        return List.copyOf(effects);
                    }

                    /**
                     * A hash of the call's arguments and of nothing else — the console shows it and uses
                     * it to tell one effect from another. Canonical (keys sorted), so two calls whose
                     * arguments differ only in arrival order hash the same: it describes the call, not
                     * the JSON it happened to be written in.
                     */
                    static String argsHash(Map<String, Object> args) {
                        StringBuilder canonical = new StringBuilder();
                        args.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(entry -> canonical.append(entry.getKey()).append('=')
                                        .append(entry.getValue()).append('&'));
                        return sha256Hex(canonical.toString());
                    }

                    private static String sha256Hex(String s) {
                        try {
                            byte[] digest = MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8));
                            StringBuilder hex = new StringBuilder(digest.length * 2);
                            for (byte b : digest) {
                                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                        .append(Character.forDigit(b & 0xF, 16));
                            }
                            return hex.toString();
                        } catch (Exception e) {
                            throw new IllegalStateException("every JRE ships SHA-256", e);
                        }
                    }
                }
                """.formatted(pkg);
    }

    private String auditChainStore(String pkg) {
        return """
                package %s.ai;

                import cn.com.keelbase.protocol.AuditChain;
                import java.time.Instant;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Objects;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                /**
                 * A hash-chained AI audit log (in-process; single-instance).
                 *
                 * <p>It answers three things, because a record that could only be written and checked is
                 * one nobody can ask anything of: rows are appended, the chain is verified, and the rows
                 * are read back — the last in the frozen {@code ai-audit-log-row} shape, which asks for
                 * two fields the chain itself does not carry.
                 *
                 * <p><b>Those two sit beside the chain, not inside it.</b> The hash covers {@code action},
                 * {@code userId} and {@code detail} and nothing else — that is the frozen
                 * {@code audit-payload} — so folding {@code isError} or {@code createdAt} in would make
                 * every row already written unverifiable. {@code isError} records a fact this application
                 * does not defend against tampering with; the other way is worse, because it would report
                 * the application's own history as a broken chain.
                 *
                 * <p><b>Which rows count as errors.</b> Whether the call went the way the caller asked:
                 * a block and a decline are not it, and neither is a proposal merely waiting on a person.
                 * An execution is recorded as one line whose outcome this application does not
                 * distinguish, its tools answering with a result rather than throwing — so a failed
                 * execution is not marked here, and that is stated rather than guessed at.
                 *
                 * 一份哈希成链的 AI 审计日志（进程内；单实例）。
                 *
                 * <p>它答三件事，因为一份**只能写、只能校验**的记录，是**没人能对它发问**的记录：行被追加、链被
                 * 校验、行被**读回来** —— 最后一件按冻结的 {@code ai-audit-log-row} 形状，而它要**两个链本身
                 * 不带的字段**。
                 *
                 * <p><b>那两个字段在链**旁边**、不在链**里面**。</b> 哈希覆盖 {@code action}、{@code userId}
                 * 与 {@code detail}，此外什么都没有 —— 那是冻结的 {@code audit-payload} —— 所以把
                 * {@code isError} 或 {@code createdAt} 折进去，会让**已经写下的每一行**都验不过。
                 * {@code isError} 记的是一个**本应用不为它防篡改**的事实；另一条路更糟，因为它会把应用**自己的
                 * 历史**报成断链。
                 *
                 * <p><b>哪些行算错误。</b>看的是这次调用**有没有按调用方所求发生**：拦截与否决都不是，**只是在等人**
                 * 的提议也不是。一次执行记成一行、而本应用**不区分它的结果**（它的工具以**结果**作答、不抛异常）——
                 * 故一次失败的执行在这里**不被标记**；这一点是**说出来**的，不是猜的。
                 */
                @Component
                public class AuditChainStore {

                    /**
                     * One row as it was written: what was recorded, who did it, when, whether it went the
                     * way the caller asked, the two hashes tying it to its predecessor, and the
                     * conversation that proposed it (null when none did).
                     *
                     * 一行，按它被写下的样子：记了什么、谁做的、什么时候、这件事有没有按调用方所求发生，把它与
                     * 前一行绑在一起的两个哈希，以及**提议它的那条会话**（没有会话时为 null）。
                     */
                    public record Row(int id, String action, String userId, String detail, Instant createdAt,
                                      boolean isError, String prevHash, String hash,
                                      String outcome, String tool, String conversationId) {
                    }

                    private final List<Row> rows = new ArrayList<>();
                    private final String key;

                    public AuditChainStore(@Value("${keelbase.audit.hmac-key:abababababababababababababababababababababababababababababababab}")
                                           String key) {
                        this.key = key;
                    }

                    /**
                     * A row for something that went the way the caller asked it to, plus the two
                     * columns C-3 added: what this proposal came to, and which tool it was about.
                     * Neither enters the hash payload — the payload is `action`/`userId`/`detail`, and
                     * folding anything else in would make every row written before it unverifiable.
                     *
                     * There is deliberately one `append` here and not three. The runtime keeps
                     * shorter overloads because published artifacts carry those signatures; this copy
                     * has no such constraint, and an overload nothing calls is a shape a reader has to
                     * check before trusting it.
                     *
                     * 一行 —— 记下这件事有没有按调用方所求发生，另加 C-3 的两列：这次提议的**结局**，以及它是
                     * 关于**哪一个工具**的。两者都不进哈希载荷 —— 载荷是 `action`/`userId`/`detail`，折进别的
                     * 东西会让**它之前写下的每一行**都验不过。
                     *
                     * **这里刻意只有一个 `append`，不是三个**：运行时保留更短的重载，是因为**已发布的
                     * artifact 带着那些签名**；而这份副本没有那条约束，**一个没人调用的重载，是读的人必须先核
                     * 一遍才敢信的形状**。
                     */
                    public synchronized void append(String action, String userId, String detail,
                                                    boolean isError, String outcome, String tool,
                                                    String conversationId) {
                        String prev = rows.isEmpty() ? null : rows.get(rows.size() - 1).hash();
                        String hash = AuditChain.hash(key, prev, payload(action, userId, detail));
                        rows.add(new Row(rows.size() + 1, action, userId, detail, Instant.now(), isError,
                                prev, hash, outcome, tool, conversationId));
                    }

                    /**
                     * The rows that name this conversation, oldest first — the governance half of a
                     * trace. A row written with no conversation (a task run) is genuinely not this
                     * conversation's and is left out, which is what "no conversation" means.
                     *
                     * 点名了**这条会话**的行，最早的在前 —— 一条追踪的治理那一半。**没有会话**时写下的行
                     * （一次任务运行）本来就不属于这条会话、故不出现，而这就是「没有会话」的意思。
                     */
                    public synchronized List<Row> rowsFor(String conversationId) {
                        return rows.stream().filter(row -> conversationId.equals(row.conversationId())).toList();
                    }

                    /**
                     * The rows, oldest first — the order the chain was built in. A copy, so a caller
                     * reading the chain cannot append to it by holding on to the list.
                     *
                     * 那些行，最早的在前 —— 也就是链被建立起来的顺序。是**副本**，故读链的调用方**不能**靠持有这个
                     * 列表往链上追加。
                     */
                    public synchronized List<Row> rows() {
                        return List.copyOf(rows);
                    }

                    public synchronized boolean verify() {
                        for (int i = 0; i < rows.size(); i++) {
                            Row row = rows.get(i);
                            String prev = i == 0 ? null : rows.get(i - 1).hash();
                            if (!Objects.equals(prev, row.prevHash())) {
                                return false;
                            }
                            if (!AuditChain.hash(key, prev, payload(row.action(), row.userId(), row.detail()))
                                    .equals(row.hash())) {
                                return false;
                            }
                        }
                        return true;
                    }

                    public synchronized int size() {
                        return rows.size();
                    }

                    /** The hashed payload — and the only three fields the chain covers. */
                    private static Map<String, Object> payload(String action, String userId, String detail) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("action", action);
                        payload.put("userId", userId);
                        payload.put("detail", detail);
                        return payload;
                    }
                }
                """.formatted(pkg);
    }

    private String governanceEngine(String pkg) {
        return """
                package %s.ai;

                import cn.com.keelbase.protocol.CanonicalJson;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import org.springframework.stereotype.Service;

                /**
                 * The trust loop: gate(risk) -> ALLOW executes / CONFIRM waits / BLOCK never runs.
                 * Every call is audited; every write records a side effect.
                 */
                @Service
                public class GovernanceEngine {

                    private final ToolRegistry registry;
                    private final GovernanceGate gate;
                    private final ConfirmationStore confirmations;
                    private final SideEffectStore sideEffects;
                    private final AuditChainStore audit;
                    private final ConfirmationWatchers watchers;

                    public GovernanceEngine(ToolRegistry registry, GovernanceGate gate,
                                            ConfirmationStore confirmations, SideEffectStore sideEffects,
                                            AuditChainStore audit, ConfirmationWatchers watchers) {
                        this.registry = registry;
                        this.gate = gate;
                        this.confirmations = confirmations;
                        this.sideEffects = sideEffects;
                        this.audit = audit;
                        this.watchers = watchers;
                    }

                    /**
                     * Gate and (if allowed) run a tool call, recording the conversation it was proposed
                     * in beside every row it writes — so a trace can merge the governance half back into
                     * the conversation that proposed it. {@code conversationId} is null for a call made
                     * with no conversation (a task run); such a row is genuinely nobody's conversation's
                     * and appears in no trace.
                     *
                     * 门控并在允许时跑一次工具调用，同时把提议它的**那条会话**记在它写下的每一行**旁边** ——
                     * 于是追踪能把治理那一半并回提议它的那条会话。{@code conversationId} 在**没有会话**的调用
                     * （一次任务运行）下为 null；那样的一行本来不属于任何会话，也不出现在任何追踪里。
                     */
                    public Map<String, Object> execute(String toolName, Map<String, Object> args, String userId,
                                                       String conversationId) {
                        AiTool tool = registry.require(toolName);
                        String decision = gate.decide(tool);
                        if ("BLOCK".equals(decision)) {
                            // Whether a row is an error is whether the call went the way the caller asked —
                            // the rule the runtime applies. A block is a "no"; a proposal merely waiting on
                            // a person is not.
                            //
                            // 一行是不是错误，看的是这次调用**有没有按调用方所求发生** —— 运行时用的就是这条规则。
                            // 拦截是一个「没有」；**只是在等人**的提议不是。
                            audit.append("tool_call", userId, toolName + " blocked", true,
                                    "blocked", toolName, conversationId);
                            return status("blocked");
                        }
                        if ("ALLOW".equals(decision)) {
                            return run(tool, args, userId, conversationId);
                        }
                        String token = confirmations.create(userId, toolName, args, tool.riskLevel(),
                                conversationId);
                        audit.append("tool_call", userId, toolName + " pending confirmation",
                                false, "pending", toolName, conversationId);
                        Map<String, Object> out = status("pending_confirmation");
                        out.put("token", token);
                        return out;
                    }

                    /**
                     * Approve and perform the write. <b>The claim comes first</b>: only the caller that
                     * takes the token out of pending reaches the tool, so a second approval arriving at
                     * the same moment is refused rather than executing the write a second time. The
                     * conversation the proposal named rides along on the pending record, because the
                     * approval is a request of its own and would otherwise not know one.
                     *
                     * 批准并执行这次写。<b>先取走声明</b>：只有把 token 从待决里取走的那个调用方够得到工具，
                     * 故同一时刻到达的第二次批准会被拒、而不是把这次写执行两遍。提议点名的那条会话**跟着待决记录
                     * 一起走**，因为批准是一个自己的请求、否则无从知道。
                     */
                    public Map<String, Object> approve(String token, String userId) {
                        ConfirmationStore.Pending pending = confirmations.claim(token, userId);
                        AiTool tool = registry.require(pending.toolName());
                        audit.append("tool_confirmation", userId, tool.name() + " approved",
                                false, "approved", tool.name(), pending.conversationId());
                        Map<String, Object> outcome =
                                run(tool, confirmations.args(pending), userId, pending.conversationId());
                        // Last, and after the write is done: whoever is watching the stream hears about the
                        // decision once it is a fact. Nothing is waiting when nobody opened a stream, and
                        // that is an ordinary outcome rather than a failure.
                        watchers.decided(token, decision("approve", pending.toolName(), outcome));
                        return outcome;
                    }

                    /** Decline — the token is consumed and nothing is written. */
                    public Map<String, Object> decline(String token, String userId) {
                        ConfirmationStore.Pending pending = confirmations.claim(token, userId);
                        // A decline is the caller not getting what they asked for, so it is an error row —
                        // and the one that says a human said no, which is what an operator comes looking for.
                        //
                        // 否决就是调用方**没拿到所求**，故它是一行错误 —— 也是「有人说了不」的那一行，而正是
                        // 操作者会来找的那一行。
                        audit.append("tool_confirmation", userId, pending.toolName() + " declined", true,
                                "declined", pending.toolName(), pending.conversationId());
                        watchers.decided(token, decision("decline", pending.toolName(), null));
                        return Map.of("status", "declined");
                    }

                    /**
                     * What to tell a waiting stream: the decision word, whether it approved, whether the
                     * tool ran, and the result if it did — the fields the console's own decision model
                     * reads, and the reason it reads {@code decision} rather than only {@code approved}.
                     */
                    private Map<String, Object> decision(String decision, String toolName,
                                                         Map<String, Object> outcome) {
                        Map<String, Object> told = new LinkedHashMap<>();
                        told.put("toolName", toolName);
                        told.put("decision", decision);
                        told.put("approved", "approve".equals(decision));
                        told.put("success", outcome != null && "executed".equals(outcome.get("status")));
                        if (outcome != null && outcome.get("effectId") != null) {
                            told.put("resultId", outcome.get("effectId"));
                        }
                        if (outcome != null && outcome.get("error") != null) {
                            told.put("error", outcome.get("error"));
                        }
                        return told;
                    }

                    private Map<String, Object> run(AiTool tool, Map<String, Object> args, String userId,
                                                    String conversationId) {
                        // The intent row (D36 ②): written before the tool runs, so "who tried" never
                        // goes missing even when the tool's own write lands and the bookkeeping after
                        // it does not. Its outcome is the line after it.
                        //
                        // intent 行（D36 ②）：写在工具跑**之前**，好让「谁试过」**永不缺行** —— 即便工具
                        // 自己那一笔落了地、而它后面的记账没有。它的结局在**它后面那一行**。
                        audit.append("tool_call", userId, tool.name() + " attempted", false, "attempted",
                                tool.name(), conversationId);
                        Map<String, Object> result = tool.execute(args, userId);
                        Long effectId = null;
                        if (Boolean.TRUE.equals(result.get("success")) && result.get("resultId") instanceof Number n) {
                            Long resultId = n.longValue();
                            // The tool's *declared* result type, not its name: the console groups effects
                            // by what they produced. The arguments go in with it — the effect's argsHash
                            // is what the console shows, and a record that dropped them could not carry one.
                            // What the tool produced, captured here — the only moment it is still what the
                            // decision produced rather than whatever the row has since become. The frozen
                            // `traceItem` reads it as `afterSnapshot`.
                            //
                            // 工具**产出了什么**，就在这一刻捕获 —— 这是它**仍然是这次决策产出的那个样子**的
                            // 唯一时刻，再往后读到的就是这一行后来变成的样子了。冻结的 `traceItem` 把它读作
                            // `afterSnapshot`。
                            effectId = sideEffects.record(userId, tool.name(), tool.resultType(), resultId,
                                    args, CanonicalJson.json(result), conversationId).id();
                        }
                        audit.append("tool_call", userId, tool.name() + " -> ok",
                                false, "executed", tool.name(), conversationId);
                        Map<String, Object> out = status("executed");
                        // The tool's answer is reported as `data`, the name the chat response uses —
                        // so the engine's own outcome and the wire shape are the same field set.
                        out.put("data", result);
                        if (effectId != null) {
                            out.put("effectId", effectId);
                        }
                        return out;
                    }

                    private static Map<String, Object> status(String status) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("status", status);
                        return m;
                    }
                }
                """.formatted(pkg);
    }

    private String toolClass(String pkg, ToolSpec tool, BusinessSpec spec) {
        // The write tool persists the spec's "detail" entity (the second one); the read tool is a stub.
        // The write body reads the args by the names `customerId` and `note`, and the generated planner
        // proposes exactly those names — so the proposal and the tool body are one contract in two
        // places. A deployment that replaces the planner with a model has to hand the tools those same
        // keys; nothing checks that for it. (Compare `ruleBasedPlanner`, which names the same coupling
        // from the planner side; this is the tool side of the same fragile point, ADR-0013 §5.1.)
        EntitySpec detail = spec.entities().size() > 1 ? spec.entities().get(1) : spec.entities().get(0);
        String body;
        if (tool.requiresConfirmation()) {
            body = """
                            %s entity = new %s();
                            entity.setCustomerId(args.get("customerId") instanceof Number n ? n.longValue() : null);
                            entity.setNote(args.get("note") == null ? "" : String.valueOf(args.get("note")));
                            entity.setOwnerUserId(userId);
                            %s saved = %sRepository.save(entity);
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("success", true);
                            result.put("resultId", saved.getId());
                            return result;
                    """
                    .formatted(detail.name(), detail.name(), detail.name(), decap(detail.name()));
        } else {
            body = """
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("success", true);
                            result.put("tool", name());
                            result.put("args", args);
                            return result;
                    """;
        }
        return """
                package %s.ai;

                import %s.domain.%s;
                import %s.domain.%sRepository;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import org.springframework.stereotype.Component;

                /** %s */
                @Component
                public class %sTool implements AiTool {

                    private final %sRepository %sRepository;

                    public %sTool(%sRepository %sRepository) {
                        this.%sRepository = %sRepository;
                    }

                    @Override
                    public String name() {
                        return "%s";
                    }

                    @Override
                    public String description() {
                        return %s;
                    }

                    @Override
                    public String riskLevel() {
                        return "%s";
                    }

                    /** What this tool writes, or null when it writes nothing. */
                    @Override
                    public String resultType() {
                        return %s;
                    }

                    @Override
                    public Map<String, Object> execute(Map<String, Object> args, String userId) {
                %s    }
                }
                """
                .formatted(pkg, pkg, detail.name(), pkg, detail.name(), tool.description(), pascal(tool.name()),
                        detail.name(), decap(detail.name()), pascal(tool.name()), detail.name(), decap(detail.name()),
                        decap(detail.name()), decap(detail.name()), tool.name(),
                        literal(tool.description()), tool.riskLevel(),
                        tool.resultType() == null ? "null" : "\"" + tool.resultType() + "\"", body);
    }

    // ── AI seams: the planner and the replier (ADR-0013 D2/D3) ──────────────────

    /**
     * Where this application decides what to call. A proposal, never a permission: everything the gate
     * applies comes from the tool's own declaration, downstream and unconditional (ADR-0013, hard rule 7).
     */
    private String toolCallPlanner(String pkg) {
        return """
                package %s.ai;

                import java.util.Map;
                import java.util.Optional;

                /**
                 * Where this application decides what to call — the seam a model plugs into, if the
                 * deployment has one.
                 *
                 * <p>A planner <b>proposes</b>: a tool and its arguments. It never decides whether the
                 * call may run. The risk level, the confirmation requirement, the audit entry and the
                 * revoke path all come from the tool's own declaration and are applied by
                 * {@link GovernanceEngine}, downstream and unconditionally — so a planner, however
                 * clever and whichever model drives it, cannot propose its way past the gate.
                 *
                 * <p>This application ships one implementation, routed by the words the business
                 * specification declares for each tool ({@link RuleBasedPlanner}). A deployment that
                 * configures a model declares its own bean and that one steps aside. Model and provider
                 * configuration are deliberately not here: choosing a provider is a deployment concern,
                 * not this application's.
                 */
                public interface ToolCallPlanner {

                    /**
                     * What to call for this message, or empty when nothing fits.
                     *
                     * @param message the caller's words
                     * @param context the caller-supplied fields a plan may draw on (e.g. an entity id);
                     *                never authorization facts, which come from the identity
                     */
                    Optional<Proposal> plan(String message, Map<String, Object> context);
                }
                """.formatted(pkg);
    }

    /** What a planner proposed: a tool and its arguments, and nothing that could clear the gate. */
    private String proposal(String pkg) {
        return """
                package %s.ai;

                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.Map;

                /**
                 * What a planner decided to call: a tool and its arguments, and nothing else.
                 *
                 * <p>It deliberately carries no risk level and no confirmation flag. Those are the
                 * engine's facts, read from the tool's own declaration — a proposal that could set them
                 * would be one that could talk its way past the gate.
                 *
                 * @param tool the tool to call, by name
                 * @param args the arguments to call it with
                 */
                public record Proposal(String tool, Map<String, Object> args) {

                    public Proposal {
                        // A defensive copy that tolerates null values — an absent argument is a real thing
                        // to pass on, and Map.copyOf would reject it.
                        args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
                    }
                }
                """.formatted(pkg);
    }

    /**
     * The default planner: a deterministic router over the message, built from the trigger words the
     * business specification carries for each tool.
     *
     * <p>This is a <b>fallback for an application that runs without a model</b>, not a classifier. A
     * deployment that puts a model behind {@link ToolCallPlanner} never reads these words — the whole
     * point of the seam is that the model decides. It is registered by
     * {@link ChatPipelineConfiguration}, and that registration yields to a deployment's own bean.
     */
    private String ruleBasedPlanner(String pkg, BusinessSpec spec) {
        StringBuilder branches = new StringBuilder();
        for (ToolSpec tool : spec.tools()) {
            if (tool.triggers() == null || tool.triggers().isEmpty()) {
                // A tool the spec gives no words for is simply not routed by this fallback.
                continue;
            }
            StringBuilder condition = new StringBuilder();
            for (String trigger : tool.triggers()) {
                if (condition.length() > 0) {
                    condition.append(" || ");
                }
                condition.append("text.contains(").append(literal(trigger)).append(')');
            }
            branches.append("        if (").append(condition).append(") {\n");
            branches.append("            Map<String, Object> args = new LinkedHashMap<>();\n");
            branches.append("            if (context.get(\"customerId\") instanceof Number customerId) {\n");
            branches.append("                args.put(\"customerId\", customerId.longValue());\n");
            branches.append("            }\n");
            if (tool.requiresConfirmation()) {
                // The write tool's body reads a `note`; the caller's own words are what it records.
                branches.append("            args.put(\"note\", text);\n");
            }
            branches.append("            return Optional.of(new Proposal(").append(literal(tool.name()))
                    .append(", args));\n");
            branches.append("        }\n");
        }
        return """
                package %s.ai;

                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.Optional;

                /**
                 * The default planner: a deterministic router over the message, built from the trigger
                 * words the business specification carries for each tool.
                 *
                 * <p>This is a <b>fallback for an application that runs without a model</b>, not a
                 * classifier and not "AI". It routes a message to a tool by words the request itself used;
                 * a deployment that puts a model behind {@link ToolCallPlanner} replaces this bean and
                 * these words are never read.
                 *
                 * <p><b>The arguments are a fixed shape, and the tools read that same shape.</b> The write
                 * tool reads {@code customerId} and {@code note}; the read tool ignores its arguments. The
                 * names are shared between this proposal and the tool body and nothing checks them — a
                 * planner replaced by a model has to hand the tools those same keys. This is the one place
                 * an adapter can get a call wrong in a way the rest of this application would not catch
                 * (ADR-0013 §5.1).
                 *
                 * <p>An argument that is not there is left out rather than invented: a message that names
                 * no customer still routes, and the tool decides what it can do without one.
                 */
                public class RuleBasedPlanner implements ToolCallPlanner {

                    @Override
                    public Optional<Proposal> plan(String message, Map<String, Object> context) {
                        String text = message == null ? "" : message;
                %s        return Optional.empty();
                    }
                }
                """.formatted(pkg, branches);
    }

    /** Where the words come from — the second seam, alongside the planner. */
    private String chatReplier(String pkg) {
        return """
                package %s.ai;

                import java.util.List;
                import java.util.Map;

                /**
                 * Where the words come from — the second AI seam, alongside {@link ToolCallPlanner}.
                 *
                 * <p>The planner answers "what should be called"; this answers "what should be said". They
                 * are separate because an application can have one without the other: the generated router
                 * routes without a model, and the deterministic replier describes what happened without
                 * one.
                 *
                 * <p>What the engine did is handed in as a fact, never something a replier can influence:
                 * the risk level, the confirmation and the audit entry are applied downstream and
                 * unconditionally.
                 */
                public interface ChatReplier {

                    /**
                     * What to say back.
                     *
                     * @param message the caller's words
                     * @param history the conversation so far, oldest first — what a model needs to answer
                     *                in context
                     * @param outcome what the engine did with this turn, or {@code null} when nothing was
                     *                routed. A replier describes it; it never decides it.
                     */
                    Reply reply(String message, List<ChatTurn> history, Map<String, Object> outcome);
                }
                """.formatted(pkg);
    }

    /** What a replier decided to say, and who said it. */
    private String reply(String pkg) {
        return """
                package %s.ai;

                /**
                 * What a {@link ChatReplier} decided to say, and who said it.
                 *
                 * <p>{@code provider} and {@code model} are not decoration: a caller has to be able to
                 * tell a deterministic fallback from a model's answer, and a reply that left them blank
                 * would make the two indistinguishable on the wire. {@link DeterministicReplier} names
                 * itself.
                 */
                public record Reply(String text, String provider, String model) {

                    public Reply {
                        if (text == null) {
                            throw new IllegalArgumentException("a reply has text");
                        }
                    }
                }
                """.formatted(pkg);
    }

    /** One turn handed to a replier — a role and what was said, not the stored entity. */
    private String chatTurn(String pkg) {
        return """
                package %s.ai;

                /**
                 * One turn handed to a {@link ChatReplier} — a role and what was said, and nothing else.
                 *
                 * <p>A separate type rather than the stored entity: what a replier needs is the
                 * conversation, not a handle on this application's persistence.
                 *
                 * @param role {@code user} or {@code assistant}
                 */
                public record ChatTurn(String role, String content) {
                }
                """.formatted(pkg);
    }

    /**
     * The default replier: it describes what the engine did, without a model.
     *
     * <p>Held to one rule a fallback is especially prone to breaking — <b>it must not read as though a
     * model wrote it.</b> Every sentence is derived from an outcome the engine actually produced, and
     * {@link #PROVIDER} says outright that no model was involved (ADR-0013 D3).
     */
    private String deterministicReplier(String pkg) {
        return """
                package %s.ai;

                import java.util.List;
                import java.util.Map;

                /**
                 * The default replier: it describes what the engine did, without a model.
                 *
                 * <p>It is held to one rule a fallback is especially prone to breaking: <b>it must not
                 * read as though a model wrote it.</b> Every sentence here is derived from an outcome the
                 * engine actually produced, and {@link #PROVIDER} says outright that no model was
                 * involved. A deployment that wants words from a model replaces this bean; a deployment
                 * that does not gets something true and plainly mechanical, which is the honest
                 * alternative to an invented answer.
                 */
                public class DeterministicReplier implements ChatReplier {

                    /** Named as what it is. A caller can tell this apart from a model's answer without guessing. */
                    public static final String PROVIDER = "deterministic";

                    public static final String MODEL = "none";

                    @Override
                    public Reply reply(String message, List<ChatTurn> history, Map<String, Object> outcome) {
                        return new Reply(describe(outcome), PROVIDER, MODEL);
                    }

                    /**
                     * What to say about this turn. Deliberately flat and specific: it reports the engine's
                     * answer and never speculates past it.
                     *
                     * <p>It also spells out what the tool answered, and that is load-bearing rather than
                     * decorative: the frozen {@code chat-response} has no field for a tool's result — its
                     * fields are the conversation turn — so the reply is the only place a result can
                     * reach a caller of the non-streaming path. A model would phrase it; this one lists it.
                     *
                     * 这个回合该说什么。刻意做到平实而具体：它报的是**引擎的答案**，绝不超出它去揣测。
                     *
                     * <p>它**还把工具答了什么说清楚**，而这是**承重**的、不是装饰：冻结的
                     * {@code chat-response} **没有放工具结果的地方** —— 它的字段是**对话回合** —— 所以 reply 是
                     * 结果能到达**非流式**调用方的**唯一**去处。模型会把它**措辞**出来；这一个把它**列出来**。
                     */
                    private String describe(Map<String, Object> outcome) {
                        if (outcome == null) {
                            return "I could not match that to a tool I can call, so I proposed nothing.";
                        }
                        Object status = outcome.get("status");
                        String said = switch (status == null ? "" : status.toString()) {
                            case "pending_confirmation" ->
                                    "I proposed a write. It is waiting for your confirmation, and nothing has "
                                            + "been written yet.";
                            case "executed" -> "Done — it ran, and the side effect is recorded.";
                            case "requires_approval" -> "That needs approval before it can run.";
                            case "blocked" -> "That was blocked by the risk policy, so nothing ran.";
                            case "declined" -> "You declined it, so nothing was written.";
                            case "error" -> "It failed: " + (outcome.get("error") == null
                                    ? "no detail given" : outcome.get("error"));
                            default -> "The engine answered: " + status + ".";
                        };
                        return said + answered(outcome);
                    }

                    /**
                     * The tool's own answer, listed. Keys are sorted so the same result reads the same way
                     * every time — a reply that reordered itself between two calls would look like two
                     * different answers.
                     *
                     * 工具**自己的答案**，逐项列出。键**排序**，故同一个结果每次读起来都一样 —— 一个在两次
                     * 调用之间自己换了次序的 reply，看起来就像**两个不同的答案**。
                     */
                    private static String answered(Map<String, Object> outcome) {
                        Object data = outcome.get("data");
                        if (!(data instanceof Map<?, ?> values) || values.isEmpty()) {
                            return "";
                        }
                        String listed = values.entrySet().stream()
                                .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .collect(java.util.stream.Collectors.joining(" "));
                        return " It answered: " + listed + ".";
                    }
                }
                """.formatted(pkg);
    }

    /**
     * Registers the deterministic planner and replier as defaults, not fixtures.
     *
     * <p>{@code @ConditionalOnMissingBean} on each bean, so a deployment that declares its own planner or
     * replier — a model-backed adapter — makes the matching default step aside. One configuration, two
     * independent seams: replacing the planner does not replace the replier.
     */
    private String chatPipelineConfiguration(String pkg) {
        return """
                package %s.ai;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * Registers the deterministic planner and replier as defaults, not fixtures.
                 *
                 * <p>Both are behind {@code @ConditionalOnMissingBean}, so a deployment that declares its
                 * own {@link ToolCallPlanner} or {@link ChatReplier} — a model-backed one — makes the
                 * matching default step aside. Nothing else changes: the engine applies the same
                 * governance to whatever the planner proposes, whichever model wrote the words.
                 *
                 * <p>The generated routing words matter only while this default is in place. With a model
                 * behind the seam they are never read.
                 */
                @Configuration
                public class ChatPipelineConfiguration {

                    @Bean
                    @ConditionalOnMissingBean(ToolCallPlanner.class)
                    ToolCallPlanner ruleBasedPlanner() {
                        return new RuleBasedPlanner();
                    }

                    @Bean
                    @ConditionalOnMissingBean(ChatReplier.class)
                    ChatReplier deterministicReplier() {
                        return new DeterministicReplier();
                    }
                }
                """.formatted(pkg);
    }

    // ── identity seam + authorization (self-contained) ──────────────────────────

    private String principal(String pkg) {
        return """
                package %s.identity;

                /**
                 * The acting identity — this app's projection of the frozen KeelBase identity vocabulary.
                 *
                 * <p>{@link #role()} is normalised into the contract's role set ({@code user} /
                 * {@code admin}); a business role (here {@code manager}) is an alias for {@code admin}
                 * rather than a separate concept, so no caller has to compare raw role strings again.
                 *
                 * <p>Unlike the runtime's principal this one carries no organization scope: a generated
                 * application has no organization store, and claiming a scope it cannot know would be
                 * inventing a fact. An adapter wired to a real directory fills one in.
                 */
                public record Principal(String userId, String role, String oidcSubject) {

                    public static final String ROLE_USER = "user";
                    public static final String ROLE_ADMIN = "admin";

                    /** A business alias for the contract's {@code admin} role. */
                    public static final String ROLE_ALIAS_MANAGER = "manager";

                    public Principal {
                        role = ROLE_ADMIN.equals(role) || ROLE_ALIAS_MANAGER.equals(role)
                                ? ROLE_ADMIN
                                : ROLE_USER;
                    }

                    /** The unified identity mapping key ({@code delegation-token-claims} {@code sub}). */
                    public String subject() {
                        return oidcSubject != null ? oidcSubject : "local:" + userId;
                    }

                    public boolean isManager() {
                        return ROLE_ADMIN.equals(role);
                    }
                }
                """.formatted(pkg);
    }

    private String identityEvidence(String pkg) {
        return """
                package %s.identity;

                import java.util.LinkedHashMap;
                import java.util.Map;

                /**
                 * What the deployment can tell the app about the caller, before it means anything.
                 *
                 * <p>Evidence is not identity: nothing here is trusted until a resolver has validated it
                 * and mapped it onto the frozen contracts. A flat attribute map keeps the seam from
                 * privileging one carrier — headers today, verified OIDC claims or a directory bind
                 * result later — without changing anything downstream.
                 */
                public record IdentityEvidence(Map<String, String> attributes) {

                    public static final String USER_ID = "userId";
                    public static final String ROLE = "role";
                    public static final String OIDC_SUBJECT = "oidcSubject";
                    /** The {@code Authorization} header as it arrived — a carrier, not a claim. */
                    public static final String AUTHORIZATION = "authorization";

                    public IdentityEvidence {
                        attributes = Map.copyOf(attributes);
                    }

                    public static IdentityEvidence ofHeaders(String authorization, String userId, String role,
                            String oidcSubject) {
                        Map<String, String> attributes = new LinkedHashMap<>();
                        putIfPresent(attributes, AUTHORIZATION, authorization);
                        putIfPresent(attributes, USER_ID, userId);
                        putIfPresent(attributes, ROLE, role);
                        putIfPresent(attributes, OIDC_SUBJECT, oidcSubject);
                        return new IdentityEvidence(attributes);
                    }

                    public String attribute(String key) {
                        return attributes.get(key);
                    }

                    private static void putIfPresent(Map<String, String> target, String key, String value) {
                        if (value != null && !value.isBlank()) {
                            target.put(key, value);
                        }
                    }
                }
                """.formatted(pkg);
    }

    private String identityResolver(String pkg) {
        return """
                package %s.identity;

                /**
                 * The identity SPI — the pluggable seam between "who the deployment authenticated" and
                 * this app's wire-shaped {@link Principal}.
                 *
                 * <p>Authentication belongs to whatever already guards the deployment (Spring Security, a
                 * gateway, an SSO proxy); an adapter implements this one method and hands over the
                 * authenticated identity. Exactly one implementation must be a Spring bean.
                 */
                public interface IdentityResolver {

                    /** Map validated evidence to the acting principal. Adapters reject evidence they
                     * cannot trust. */
                    Principal resolve(IdentityEvidence evidence);
                }
                """.formatted(pkg);
    }

    private String headerIdentityResolver(String pkg) {
        return """
                package %s.identity;

                import org.springframework.http.HttpStatus;
                import org.springframework.stereotype.Component;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * For deployments behind a guard: it reads an identity the guard has already established —
                 * {@code X-User-Id} (required), {@code X-User-Role} (default {@code user}), an optional
                 * {@code X-Oidc-Sub}.
                 *
                 * <p><b>Not the default, and deliberately not a bean.</b> A role taken from a request is a
                 * role the caller grants itself, and the runtime removed exactly this adapter for exactly
                 * that reason (JV-9). It is safe only where the guard strips what the client sent and
                 * rewrites these headers itself; a deployment in that shape declares this class as its
                 * {@link IdentityResolver} bean. Everyone else gets {@link DelegationTokenIdentityResolver}.
                 */
                public class HeaderIdentityResolver implements IdentityResolver {

                    public static final String USER_ID_HEADER = "X-User-Id";
                    public static final String ROLE_HEADER = "X-User-Role";
                    public static final String OIDC_SUBJECT_HEADER = "X-Oidc-Sub";

                    @Override
                    public Principal resolve(IdentityEvidence evidence) {
                        String userId = evidence.attribute(IdentityEvidence.USER_ID);
                        if (userId == null) {
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                    "missing " + USER_ID_HEADER);
                        }
                        String role = evidence.attribute(IdentityEvidence.ROLE);
                        return new Principal(userId, role == null ? Principal.ROLE_USER : role,
                                evidence.attribute(IdentityEvidence.OIDC_SUBJECT));
                    }
                }
                """.formatted(pkg);
    }

    /**
     * Which local user and role a verified subject maps to.
     *
     * <p>The default adapter is the delegation token one, and this is where the subject it proves becomes
     * a role this deployment actually holds: declared here, not read from the request, because a role the
     * caller states is a role the caller grants itself.
     */
    private String localIdentities(String pkg) {
        return """
                package %s.identity;

                import java.util.Map;
                import java.util.Optional;
                import org.springframework.stereotype.Component;

                /**
                 * The local identity a verified subject maps to.
                 *
                 * <p><b>Declared, not looked up.</b> This application has no user table: which user and which
                 * role a subject holds is this deployment's statement, which is why it lives in code a
                 * deployment can edit rather than in a request it cannot trust.
                 *
                 * <p>The entries below are the demo identities the demos mint tokens for. A deployment
                 * replaces them with its own directory — or the whole class with an implementation that
                 * reads one.
                 */
                @Component
                public class LocalIdentities {

                    /** A local identity: the user a subject maps to, and the role they hold here. */
                    public record Entry(String userId, String role) {
                    }

                    private final Map<String, Entry> bySubject;

                    public LocalIdentities() {
                        this(Map.of(
                                "local:alice", new Entry("alice", Principal.ROLE_USER),
                                "local:bob", new Entry("bob", Principal.ROLE_USER),
                                "local:carol", new Entry("carol", Principal.ROLE_ADMIN)));
                    }

                    public LocalIdentities(Map<String, Entry> bySubject) {
                        this.bySubject = Map.copyOf(bySubject);
                    }

                    /**
                     * The identity a verified subject maps to, or empty when this deployment does not know
                     * it. Unknown means unknown: the caller is refused rather than defaulted to a role this
                     * deployment never granted.
                     */
                    public Optional<Entry> lookup(String subject) {
                        return Optional.ofNullable(bySubject.get(subject));
                    }

                    /**
                     * The subject a user id is filed under, or empty when this deployment does not know
                     * the user.
                     *
                     * <p>The login path is the one caller that looks a person up by the id they type
                     * rather than by the subject their IdP issues — so this is the one lookup that has to
                     * search by value. It answers with the <b>key</b>, not the entry, so the session it
                     * mints names the same subject the rest of the chain already resolves.
                     *
                     * <p>登录那条路是唯一一个「按人<b>敲进去的</b>用户 id、而不是按 IdP 发的 subject」
                     * 查身份的调用者——所以这是唯一一处必须<b>按值搜</b>的查找。它答的是<b>键</b>、不是条目，
                     * 好让它铸出的会话命名的正是链上其余部分已经在解析的那个 subject。
                     */
                    public Optional<String> subjectOf(String userId) {
                        return bySubject.entrySet().stream()
                                .filter(e -> e.getValue().userId().equals(userId))
                                .map(Map.Entry::getKey)
                                .findFirst();
                    }
                }
                """.formatted(pkg);
    }

    /**
     * The default adapter: the caller proves who it is with a KeelBase delegation token.
     *
     * <p>Verification is the frozen protocol's own, and the role comes from the directory — so what the
     * caller says about itself counts for nothing here, which is the difference between this and reading
     * identity off a header.
     */
    private String delegationTokenIdentityResolver(String pkg) {
        return """
                package %s.identity;

                import cn.com.keelbase.protocol.DelegationToken;
                import java.time.Instant;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.HttpStatus;
                import org.springframework.stereotype.Component;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * The default adapter: a KeelBase delegation token proves the subject, the local directory
                 * decides the role.
                 *
                 * <p>Verification is the frozen protocol's own, so this application cannot drift from the
                 * contract by re-implementing JWT checks — and a token minted by a KeelBase deployment is
                 * the same token this adapter accepts, because secret and audience are the contract's own
                 * settings. A token that fails verification leaves the caller unauthenticated; the reason is
                 * not echoed back, since it tells a prober whether the token was expired or forged.
                 */
                @Component
                public class DelegationTokenIdentityResolver implements IdentityResolver {

                    private static final String BEARER = "Bearer ";

                    private final String secret;
                    private final String audience;
                    private final LocalIdentities identities;

                    public DelegationTokenIdentityResolver(
                            @Value("${keelbase.delegation.secret}") String secret,
                            @Value("${keelbase.delegation.audience}") String audience,
                            LocalIdentities identities) {
                        this.secret = secret;
                        this.audience = audience;
                        this.identities = identities;
                    }

                    @Override
                    public Principal resolve(IdentityEvidence evidence) {
                        String header = evidence.attribute(IdentityEvidence.AUTHORIZATION);
                        if (header == null || !header.startsWith(BEARER)) {
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing delegation token");
                        }
                        DelegationToken.Result verified = DelegationToken.verify(
                                header.substring(BEARER.length()), secret, audience,
                                Instant.now().getEpochSecond());
                        if (!verified.ok()) {
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "delegation token rejected");
                        }
                        String subject = String.valueOf(verified.payload().get("sub"));
                        Object oidcSubject = verified.payload().get("oidcSub");
                        LocalIdentities.Entry entry = identities.lookup(subject).orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "this deployment does not know the subject"));
                        return new Principal(entry.userId(), entry.role(),
                                oidcSubject == null ? null : String.valueOf(oidcSubject));
                    }
                }
                """.formatted(pkg);
    }

    /**
     * The declared rules, derived from the spec: every entity grants its owner the manage set on their
     * own rows, and a policy rule reserving an action for another role removes that action from the
     * roles it was not reserved for.
     */
    private String authorizationRules(String pkg, BusinessSpec spec) {
        String ownerField = spec.roleRule().ownerField();
        StringBuilder userRules = new StringBuilder();
        for (EntitySpec entity : spec.entities()) {
            List<String> actions = new ArrayList<>(PermissionCapabilityList.EXPANDED_ACTIONS);
            for (BusinessSpec.PolicyRule policy : spec.policies()) {
                if (policy.entity().equals(entity.name())
                        && !PermissionCapabilityList.ROLE_USER.equals(contractRole(policy.requiredRole()))) {
                    actions.remove(policy.action());
                }
            }
            if (userRules.length() > 0) {
                userRules.append(",\n").append(" ".repeat(24));
            }
            userRules.append("new Rule(\"").append(entity.name()).append("\", List.of(");
            for (int i = 0; i < actions.size(); i++) {
                userRules.append(i == 0 ? "" : ", ").append('"').append(actions.get(i)).append('"');
            }
            userRules.append("), \"").append(ownerField).append("\")");
        }

        return """
                package %s.authz;

                import cn.com.keelbase.protocol.PermissionCapabilityList;
                import %s.identity.Principal;
                import java.util.List;
                import java.util.Map;
                import org.springframework.stereotype.Component;

                /**
                 * The authorization rule source — who may do what, before any decision is taken.
                 *
                 * <p>Generated from the business specification. The decisions themselves live in
                 * {@link PermissionAuthorizer}, which reads rules through {@link #rulesFor(Principal)},
                 * so replacing this source (declarations today, a table later) does not touch the
                 * semantics. There is deliberately no roles/permissions table.
                 */
                @Component
                public class AuthorizationRules {

                    /**
                     * A declared grant: {@code actions} on {@code subject}, ownership-conditioned when
                     * {@code ownerField} is set.
                     */
                    public record Rule(String subject, List<String> actions, String ownerField) {
                    }

                    private final Map<String, List<Rule>> byRole;

                    public AuthorizationRules() {
                        this.byRole = Map.of(
                                PermissionCapabilityList.ROLE_ADMIN, List.of(new Rule(
                                        PermissionCapabilityList.SUBJECT_ALL,
                                        PermissionCapabilityList.EXPANDED_ACTIONS,
                                        null)),
                                PermissionCapabilityList.ROLE_USER, List.of(
                                        %s));
                    }

                    public List<Rule> rulesFor(String role) {
                        return byRole.getOrDefault(role, List.of());
                    }

                    /**
                     * The rules for a caller. Delegates to {@link #rulesFor(String)} here, because this
                     * source is declared rather than read from a table and the role is all it needs. A
                     * source that has to answer from the caller overrides <em>this</em> one instead: the
                     * decision function asks through it, so that a deployment whose roles are its own can
                     * tell two people of one role apart without reaching into the security context, which
                     * is where the answer starts depending on the path the decision runs on.
                     */
                    public List<Rule> rulesFor(Principal principal) {
                        return rulesFor(principal.role());
                    }
                }
                """.formatted(pkg, pkg, userRules);
    }

    private String permissionAuthorizer(String pkg) {
        return """
                package %s.authz;

                import cn.com.keelbase.protocol.PermissionCapabilityList;
                import cn.com.keelbase.protocol.PermissionDecision;
                import %s.identity.Principal;
                import java.util.ArrayList;
                import java.util.Comparator;
                import java.util.List;
                import org.springframework.stereotype.Service;

                /**
                 * The authorization decision function — self-built, and the reason this app owns its
                 * authorization semantics rather than embedding a policy engine.
                 *
                 * <p>It answers in the vocabulary of the frozen contracts: {@link #decide} produces a
                 * {@code permission-decision}, {@link #describe} produces a
                 * {@code permission-capability-list}. Both take their rules from {@link AuthorizationRules}.
                 */
                @Service
                public class PermissionAuthorizer {

                    private final AuthorizationRules rules;

                    public PermissionAuthorizer(AuthorizationRules rules) {
                        this.rules = rules;
                    }

                    /** The frozen {@code permission-decision} for {@code action × subject}. */
                    public PermissionDecision decide(Principal principal, String action, String subject) {
                        AuthorizationRules.Rule rule = ruleFor(principal, subject);
                        boolean allowed = rule != null && rule.actions().contains(action);
                        String reason;
                        if (allowed) {
                            reason = PermissionCapabilityList.SUBJECT_ALL.equals(subject)
                                    ? PermissionDecision.REASON_ALLOWED_ALL
                                    : PermissionDecision.REASON_ALLOWED_OWN;
                        } else {
                            reason = principal.isManager()
                                    ? PermissionDecision.REASON_DENIED_ADMIN
                                    : PermissionDecision.REASON_DENIED_USER;
                        }
                        return new PermissionDecision(action, subject, allowed, reason,
                                allowed ? null : PermissionDecision.DENIED_BY_CASL);
                    }

                    /**
                     * The capability this principal holds on {@code subject}, or {@code null} when
                     * nothing is granted. The row-level scope lives here rather than on the decision,
                     * which is how the frozen contracts split it.
                     */
                    public PermissionCapabilityList.Resource capabilityFor(Principal principal, String subject) {
                        AuthorizationRules.Rule rule = ruleFor(principal, subject);
                        if (rule == null) {
                            return null;
                        }
                        return new PermissionCapabilityList.Resource(subject, scopeOf(rule),
                                rule.actions(), reasonOf(rule));
                    }

                    /** The frozen {@code permission-capability-list}: what this identity may do, and on
                     * what basis. */
                    public PermissionCapabilityList describe(Principal principal) {
                        List<PermissionCapabilityList.Resource> resources = new ArrayList<>();
                        for (AuthorizationRules.Rule rule : rules.rulesFor(principal)) {
                            resources.add(new PermissionCapabilityList.Resource(
                                    rule.subject(), scopeOf(rule), rule.actions(), reasonOf(rule)));
                        }
                        resources.sort(Comparator.comparing(PermissionCapabilityList.Resource::subject));
                        return new PermissionCapabilityList(principal.role(),
                                principal.isManager()
                                        ? PermissionCapabilityList.BASIS_ADMIN
                                        : PermissionCapabilityList.BASIS_USER,
                                resources);
                    }

                    private AuthorizationRules.Rule ruleFor(Principal principal, String subject) {
                        for (AuthorizationRules.Rule rule : rules.rulesFor(principal)) {
                            if (PermissionCapabilityList.SUBJECT_ALL.equals(rule.subject())
                                    || rule.subject().equals(subject)) {
                                return rule;
                            }
                        }
                        return null;
                    }

                    private static String scopeOf(AuthorizationRules.Rule rule) {
                        return rule.ownerField() == null
                                ? PermissionCapabilityList.SCOPE_ALL
                                : PermissionCapabilityList.SCOPE_OWN;
                    }

                    private static String reasonOf(AuthorizationRules.Rule rule) {
                        if (rule.ownerField() != null) {
                            return PermissionCapabilityList.REASON_RESOURCE_OWN;
                        }
                        return PermissionCapabilityList.SUBJECT_ALL.equals(rule.subject())
                                ? PermissionCapabilityList.REASON_RESOURCE_ALL
                                : PermissionCapabilityList.REASON_RESOURCE_UNRESTRICTED;
                    }
                }
                """.formatted(pkg, pkg);
    }

    private String ownershipGuard(String pkg) {
        return """
                package %s.authz;

                import cn.com.keelbase.protocol.PermissionCapabilityList;
                import cn.com.keelbase.protocol.PermissionDecision;
                import %s.identity.Principal;
                import org.springframework.http.HttpStatus;
                import org.springframework.stereotype.Component;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * Row-level enforcement — the {@code own} half of the authorization model, enforced in
                 * the app rather than described in a prompt.
                 *
                 * <p>The decision does not come from a role check here: it comes from
                 * {@link PermissionAuthorizer}, i.e. from the same frozen {@code permission-decision} /
                 * {@code permission-capability-list} semantics any other KeelBase runtime produces. So
                 * "an administrator may read any row, a user only their own" is derived from the
                 * contract, not hardcoded.
                 */
                @Component
                public class OwnershipGuard {

                    private final PermissionAuthorizer authorizer;

                    public OwnershipGuard(PermissionAuthorizer authorizer) {
                        this.authorizer = authorizer;
                    }

                    /**
                     * Throw 403 unless {@code principal} may take {@code action} on a {@code subject} row
                     * owned by {@code ownerUserId}.
                     */
                    public void requireAccess(Principal principal, String subject, String action,
                                              String ownerUserId) {
                        requireAction(principal, subject, action);
                        if (seesOwnRowsOnly(principal, subject)
                                && !principal.userId().equals(ownerUserId)) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your resource");
                        }
                    }

                    /** Throw 403 unless the principal holds {@code action} on {@code subject} at all. */
                    public void requireAction(Principal principal, String subject, String action) {
                        PermissionDecision decision = authorizer.decide(principal, action, subject);
                        if (!decision.allowed()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
                        }
                    }

                    /** True when this principal's rows on {@code subject} must be filtered to their own. */
                    public boolean seesOwnRowsOnly(Principal principal, String subject) {
                        PermissionCapabilityList.Resource capability =
                                authorizer.capabilityFor(principal, subject);
                        return capability != null
                                && PermissionCapabilityList.SCOPE_OWN.equals(capability.scope());
                    }
                }
                """.formatted(pkg, pkg);
    }

    // ── web ─────────────────────────────────────────────────────────────────────

    /**
     * A business role normalised into the contract's role set. The spec's only business alias is
     * {@code manager}, which the contract expresses as {@code admin}.
     */
    private static String contractRole(String businessRole) {
        return PermissionCapabilityList.ROLE_ADMIN.equals(businessRole) || "manager".equals(businessRole)
                ? PermissionCapabilityList.ROLE_ADMIN
                : PermissionCapabilityList.ROLE_USER;
    }

    /**
     * One turn, extracted so both endpoints run the same one (ADR-0014 D7).
     *
     * <p>The plain endpoint answers with a body built from the turn; the streaming one reports the turn
     * step by step. What must not differ between them is the turn itself — the planner proposes, the
     * engine disposes, and the reply is written afterwards from what the engine answered.
     */
    private String chatTurnService(String pkg) {
        return """
                package %s.web;

                import %s.ai.ChatReplier;
                import %s.ai.ChatTurn;
                import %s.ai.GovernanceEngine;
                import %s.ai.Proposal;
                import %s.ai.Reply;
                import %s.ai.ToolCallPlanner;
                import %s.conversation.ConversationMessage;
                import %s.conversation.ConversationStore;
                import %s.identity.Principal;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.regex.Matcher;
                import java.util.regex.Pattern;
                import org.springframework.stereotype.Service;

                /**
                 * One turn of a conversation, from the caller's words to what to say back.
                 *
                 * <p>Extracted because two endpoints run the same turn and differ only in how they report
                 * it: the plain one answers with a body, the streaming one reports the steps as they
                 * happen. Leaving the sequence in both would mean two places to keep in step, and a second
                 * implementation of the same thing drifts.
                 *
                 * <p>The sequence itself is the load-bearing part and does not change between the two: the
                 * planner proposes, the engine disposes, and the reply is written afterwards from what the
                 * engine actually answered.
                 */
                @Service
                public class ChatTurnService {

                    /**
                     * What the turn produced, in the order a caller wants to report it.
                     *
                     * @param plan    what the planner proposed, or {@code null} when nothing was routed
                     * @param outcome what the engine did about it, or {@code null} when there was nothing
                     *                to gate
                     */
                    public record Turn(String conversationId, Proposal plan, Map<String, Object> outcome,
                                       Reply reply) {
                    }

                    /**
                     * <p>One reader for this, here rather than in the planner: the reference is conversation
                     * state, and this is where the transcript is. A planner receives the resolved id in its
                     * context and routes on it.
                     *
                     * The customer the console is looking at, as it writes it into the message: 「Acme」（ID 1）.
                     */
                    private static final Pattern CUSTOMER_MARKER =
                            Pattern.compile("[（(]\\\\s*ID\\\\s*(\\\\d+)\\\\s*[）)]", Pattern.CASE_INSENSITIVE);

                    private final ToolCallPlanner planner;
                    private final ChatReplier replier;
                    private final ConversationStore conversations;
                    private final GovernanceEngine engine;

                    public ChatTurnService(ToolCallPlanner planner, ChatReplier replier,
                                           ConversationStore conversations, GovernanceEngine engine) {
                        this.planner = planner;
                        this.replier = replier;
                        this.conversations = conversations;
                        this.engine = engine;
                    }

                    /**
                     * Run one turn.
                     *
                     * @param customerId the caller's own id for the subject, or {@code null} — the
                     *                   conversation's own naming is read when it is absent
                     */
                    public Turn run(String message, Object customerId, String conversationId,
                                    Principal principal) {
                        String id = conversations.openFor(conversationId, principal.userId());
                        conversations.append(id, principal.userId(), ConversationMessage.USER, message);

                        Map<String, Object> context = new LinkedHashMap<>();
                        context.put("customerId", customerId != null ? customerId : mentionedCustomer(id));

                        Proposal plan = planner.plan(message, context).orElse(null);
                        // The planner proposes; the engine decides. Nothing the planner returns can skip
                        // this — risk, confirmation and audit are applied by the engine, unconditionally.
                        Map<String, Object> outcome = plan == null
                                ? null
                                : engine.execute(plan.tool(), plan.args(), principal.userId(), id);

                        Reply reply = replier.reply(message, turns(id), outcome);
                        conversations.append(id, principal.userId(), ConversationMessage.ASSISTANT, reply.text());
                        return new Turn(id, plan, outcome, reply);
                    }

                    /**
                     * The recent turns, oldest first. The caller's own message is already among them by the
                     * time a replier is asked, so a model reads the same conversation this application does.
                     */
                    public List<ChatTurn> turns(String conversationId) {
                        return conversations.history(conversationId).stream()
                                .map(turn -> new ChatTurn(turn.getRole(), turn.getContent()))
                                .toList();
                    }

                    /**
                     * Which customer this conversation is about, as named in its own transcript.
                     *
                     * <p>Reading the transcript is what the store is for: this is not embeddings, not
                     * retrieval and not a memory policy (ADR-0013 D4 — a transcript, not memory).
                     *
                     * <p>The console names it once — in the first message ("当前客户「Acme」（ID 1）。给客户
                     * 建一条跟进记录"), because no client sends a customer id as a field. A model reads that
                     * reference wherever it appears; an application without one has to as well, or the second
                     * write in a conversation — "再建一条" — reaches a tool with nobody to act on. So the most
                     * recent mention wins, and the caller's explicit id beats everything.
                     */
                    private Long mentionedCustomer(String conversationId) {
                        List<ConversationMessage> history = conversations.history(conversationId);
                        for (int i = history.size() - 1; i >= 0; i--) {
                            Matcher marker = CUSTOMER_MARKER.matcher(history.get(i).getContent());
                            if (marker.find()) {
                                return Long.valueOf(marker.group(1));
                            }
                        }
                        return null;
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg, pkg, pkg, pkg, pkg);
    }

    /**
     * The streaming entry point (ADR-0014, mirroring ADR-0010).
     *
     * <p>It exists because the console's writes need it, not for a typing effect: the confirmation card
     * is built from a {@code confirmation_request} event, and the drawer approves *while the stream is
     * still open*, so a stream that closed with the turn could not deliver the decision back.
     */
    private String chatStreamController(String pkg) {
        return """
                package %s.web;

                import %s.ai.AiTool;
                import %s.ai.ConfirmationWatchers;
                import %s.ai.ToolRegistry;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import cn.com.keelbase.protocol.ConfirmationLifecycle;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.concurrent.ScheduledExecutorService;
                import java.util.concurrent.ScheduledFuture;
                import java.util.concurrent.TimeUnit;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.MediaType;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;
                import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

                /**
                 * The streaming entry point, in the shape the web console reads (ADR-0014).
                 *
                 * <p>The stream ends in one of two ways. A decision arrives — from anywhere — and the
                 * stream reports it and closes. Or the wait runs out and nothing decided it, in which case
                 * the stream closes <em>without</em> a decision: nothing here expires a confirmation, so the
                 * write is still decidable out of band, and saying otherwise would be a lie the data does
                 * not support.
                 *
                 * <p>Both paths — {@code /ai/chat/stream} and {@code /admin/ai/chat/stream} — land here,
                 * because the console picks its endpoint by the caller's role. They are the same handler
                 * rather than two assistants: this application has one set of tools, and inventing a second
                 * set so that an endpoint name looks implemented is exactly the kind of thing not to do.
                 *
                 * <p><b>Why it stays open.</b> A confirmation decided on another surface has to reach an
                 * open view; the drawer's own approve comes back on its own request, but a decision taken
                 * elsewhere does not. This is the only reason this endpoint is a stream.
                 */
                @RestController
                public class ChatStreamController {

                    /**
                     * Slack over the wait, so the emitter's own timeout does not fire first and cut off the
                     * {@code done} event the caller is waiting for.
                     */
                    private static final long TIMEOUT_SLACK_MILLIS = 5_000L;

                    private final IdentityResolver identities;
                    private final ChatTurnService turns;
                    private final ConfirmationWatchers watchers;
                    private final ToolRegistry tools;
                    private final ScheduledExecutorService scheduler;
                    private final long waitMillis;

                    public ChatStreamController(IdentityResolver identities, ChatTurnService turns,
                                                ConfirmationWatchers watchers, ToolRegistry tools,
                                                ScheduledExecutorService scheduler,
                                                @Value("${keelbase.chat.stream-wait-ms:0}") long configuredWait) {
                        this.identities = identities;
                        this.turns = turns;
                        this.watchers = watchers;
                        this.tools = tools;
                        this.scheduler = scheduler;
                        // How long a stream waits for a decision. Zero means the contract's own window —
                        // the same constant the confirmation vocabulary is built on, taken from the protocol
                        // library rather than restated here.
                        this.waitMillis = configuredWait > 0 ? configuredWait : ConfirmationLifecycle.DEFAULT_TTL_MILLIS;
                    }

                    @PostMapping("/ai/chat/stream")
                    public SseEmitter stream(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject,
                            @RequestBody Map<String, Object> body) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        return open(body, principal);
                    }

                    /**
                     * The console's endpoint when its caller is an administrator. The gate is the whole
                     * difference between the two paths — same turn, same tools, same governance — and it is
                     * the reference's own arrangement: its admin route carries {@code manage all}, and this
                     * application answers the same way rather than pretending to have a second assistant
                     * behind the name.
                     */
                    @PostMapping("/admin/ai/chat/stream")
                    public SseEmitter adminStream(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject,
                            @RequestBody Map<String, Object> body) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        if (!principal.isManager()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "the management conversation endpoint is for administrators");
                        }
                        return open(body, principal);
                    }

                    private SseEmitter open(Map<String, Object> body, Principal principal) {
                        SseEmitter emitter = new SseEmitter(waitMillis + TIMEOUT_SLACK_MILLIS);
                        try {
                            String message = body.get("message") == null ? "" : String.valueOf(body.get("message"));
                            ChatTurnService.Turn turn = turns.run(
                                    message,
                                    body.get("customerId"),
                                    body.get("conversationId") == null
                                            ? null : String.valueOf(body.get("conversationId")),
                                    principal);

                            if (turn.plan() != null) {
                                send(emitter, "tool_start", Map.of("toolStart", toolStart(turn)));
                            }
                            send(emitter, "text", Map.of("content", turn.reply().text()));

                            Map<String, Object> outcome = turn.outcome();
                            if (outcome != null && "pending_confirmation".equals(outcome.get("status"))) {
                                send(emitter, "confirmation_request", Map.of("confirmation", confirmation(turn)));
                                awaitDecision(emitter, String.valueOf(outcome.get("token")), turn);
                                return emitter;
                            }
                            finish(emitter, turn);
                        } catch (RuntimeException e) {
                            emitter.completeWithError(e);
                        }
                        return emitter;
                    }

                    /**
                     * Hold the stream open until somebody decides, then report and close. Registered against
                     * the token, so the decision can arrive on a different request — which is the point.
                     */
                    private void awaitDecision(SseEmitter emitter, String token, ChatTurnService.Turn turn) {
                        watchers.watch(token,
                                decision -> {
                                    send(emitter, "confirmation_decision",
                                            Map.of("confirmationDecision", decision));
                                    send(emitter, "tool_end", Map.of("toolEnd", Map.of(
                                            "name", turn.plan().tool(),
                                            "success", Boolean.TRUE.equals(decision.get("success")))));
                                    send(emitter, "done", Map.of("conversationId", turn.conversationId()));
                                    emitter.complete();
                                },
                                () -> {
                                    send(emitter, "done", Map.of("conversationId", turn.conversationId()));
                                    emitter.complete();
                                });

                        // The wait's own deadline, a step ahead of the emitter's: the container's timeout
                        // would end the response where it stands, with no closing event, and a caller
                        // reading that cannot tell it from a dropped connection. Firing first is what lets
                        // this stream say it is done.
                        ScheduledFuture<?> expiry =
                                scheduler.schedule(() -> watchers.expired(token), waitMillis, TimeUnit.MILLISECONDS);

                        Runnable release = () -> {
                            watchers.stop(token);
                            expiry.cancel(false);
                        };
                        emitter.onCompletion(release);
                        emitter.onError(disconnected -> release.run());
                        emitter.onTimeout(release);
                    }

                    private void finish(SseEmitter emitter, ChatTurnService.Turn turn) {
                        if (turn.outcome() != null) {
                            send(emitter, "tool_end", Map.of("toolEnd", Map.of(
                                    "name", turn.plan().tool(),
                                    "success", "executed".equals(turn.outcome().get("status")))));
                        }
                        send(emitter, "done", Map.of("conversationId", turn.conversationId()));
                        emitter.complete();
                    }

                    /** A write is a tool that produces a result; a read produces none. */
                    private Map<String, Object> toolStart(ChatTurnService.Turn turn) {
                        AiTool tool = tools.require(turn.plan().tool());
                        Map<String, Object> start = new LinkedHashMap<>();
                        start.put("name", tool.name());
                        start.put("arguments", turn.plan().args());
                        start.put("isWrite", tool.resultType() != null);
                        // The risk level does belong on this channel: it goes to the user's own console,
                        // not to a model. The rule about withholding it is about prompts.
                        start.put("riskLevel", tool.riskLevel());
                        return start;
                    }

                    private Map<String, Object> confirmation(ChatTurnService.Turn turn) {
                        Map<String, Object> confirmation = new LinkedHashMap<>();
                        // The token is the only way the console learns what to approve.
                        confirmation.put("token", turn.outcome().get("token"));
                        confirmation.put("toolName", turn.plan().tool());
                        confirmation.put("arguments", turn.plan().args());
                        // R3 — the operator confirms their own write, which the frozen vocabulary calls
                        // `immediate`. This application implements neither of the other two, and saying it
                        // did would put a claim on the wire that nothing backs.
                        confirmation.put("mode", "immediate");
                        return confirmation;
                    }

                    /**
                     * One event. The {@code type} travels inside the JSON as well as on the {@code event:}
                     * line, because that is what the console parses; the name is there for anything reading
                     * the stream directly.
                     */
                    private void send(SseEmitter emitter, String type, Map<String, Object> payload) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("type", type);
                        event.putAll(payload);
                        try {
                            emitter.send(SseEmitter.event().name(type).data(event, MediaType.APPLICATION_JSON));
                        } catch (Exception e) {
                            throw new IllegalStateException("could not send the '" + type + "' event", e);
                        }
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg, pkg);
    }

    /**
     * The one piece of infrastructure the streaming endpoint needs: something that can fire a wait's
     * deadline a step before the connection's own.
     *
     * <p>Explicit rather than auto-configured. This application ships no scheduling configuration, and
     * leaning on framework defaults for a timer that has to beat a connection deadline is the kind of
     * implicit dependency that breaks on an upgrade with no test noticing.
     */
    private String chatStreamConfiguration(String pkg) {
        return """
                package %s.web;

                import java.util.concurrent.Executors;
                import java.util.concurrent.ScheduledExecutorService;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /** The scheduler behind a stream's wait deadline (ADR-0014 D6). */
                @Configuration
                public class ChatStreamConfiguration {

                    /** A daemon thread: no wait outlives the application. */
                    @Bean(destroyMethod = "shutdown")
                    public ScheduledExecutorService chatStreamScheduler() {
                        return Executors.newSingleThreadScheduledExecutor(runnable -> {
                            Thread thread = new Thread(runnable, "chat-stream-wait");
                            thread.setDaemon(true);
                            return thread;
                        });
                    }
                }
                """.formatted(pkg);
    }

    private String aiController(String pkg) {
        return """
                package %s.web;

                import %s.ai.GovernanceEngine;
                import %s.ai.GovernanceGate;
                import %s.ai.ToolRegistry;
                import cn.com.keelbase.protocol.RiskLevel;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * The AI entry point, non-streaming.
                 *
                 * <p>{@code POST /ai/chat} takes a <b>message</b> and answers the conversation shape the
                 * reference implementation and the KeelBase4J runtime both answer, field for field, so a
                 * client written against either works unchanged: {@code conversationId}, {@code reply},
                 * {@code provider}, {@code model} and an optional {@code toolCalls}. That is the frozen
                 * {@code chat-response} and nothing more — a tool call's result is reported as events on
                 * {@link ChatStreamController}, and a non-streaming call returns no confirmation token.
                 *
                 * <p>The turn itself — planner, engine, replier — is {@link ChatTurnService}, because the
                 * streaming endpoint runs the same one and must not grow a second copy of it (ADR-0014 D7).
                 * This class only decides how to report it: as one body. A message that routes to nothing is
                 * not an error here: it answers with a reply that says so, which is what a chat surface
                 * expects.
                 */
                @RestController
                public class AiController {

                    private final ToolRegistry registry;
                    private final ChatTurnService turns;
                    private final GovernanceEngine engine;
                    private final GovernanceGate gate;
                    private final IdentityResolver identities;

                    public AiController(ToolRegistry registry, ChatTurnService turns, GovernanceEngine engine,
                                        GovernanceGate gate, IdentityResolver identities) {
                        this.registry = registry;
                        this.turns = turns;
                        this.engine = engine;
                        this.gate = gate;
                        this.identities = identities;
                    }

                    // The catalogue is administration, not a public list: it is the same surface the
                    // runtime hands only to an admin, and a caller who may not govern these tools has no
                    // business enumerating them. Refused rather than filtered — there is no subset of
                    // this list a plain caller is entitled to. (The readiness probes in the demo scripts
                    // ask /app/capabilities instead: a probe should ask what any caller may ask.)
                    //
                    // 这份名录属于**管理面**、不是公开列表：运行时只把它交给管理员，而一个无权治理这些工具的
                    // 调用者也没有理由去清点它们。**拒绝**而不是过滤——这份列表里没有一个子集是普通调用者有权看
                    // 到的。（演示脚本里的就绪探针改问 `/app/capabilities`：探针该问**任何调用者都能问**的东西。）
                    @GetMapping("/ai/tools")
                    public List<Map<String, Object>> tools(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        if (!principal.isManager()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "the tool catalogue is administration");
                        }
                        return registry.all().stream().map(tool -> {
                            // The governance half is asked of the very gate the engine runs, never restated,
                            // so a tool the gate would confirm cannot be listed as one that runs by itself.
                            //
                            // 治理那一半向**引擎自己用的那道闸**问来、从不复述：闸要确认的工具，不可能被列成
                            // 一个**自己就会跑**的工具。
                            String decision = gate.decide(tool);
                            Map<String, Object> view = new LinkedHashMap<>();
                            view.put("name", tool.name());
                            view.put("description", tool.description());
                            // This application declares no parameter list, so the object's required array is
                            // empty rather than invented — the same "nothing was declared" the runtime's own
                            // list reports for its unset dimensions.
                            //
                            // 本应用不声明参数表，故对象要求的那数组**空着**、不是编一个 —— 与运行时自己的清单
                            // 对未设的维度报的是同一句「什么都没声明」。
                            view.put("parameters", List.of());
                            view.put("enabled", true);
                            view.put("requiresConfirmation",
                                    "CONFIRM".equals(decision) || "REQUIRE_APPROVAL".equals(decision));
                            view.put("requiresApproval", "REQUIRE_APPROVAL".equals(decision));
                            view.put("gateMode", switch (decision) {
                                case "BLOCK" -> "blocked";
                                case "CONFIRM" -> "confirm";
                                case "REQUIRE_APPROVAL" -> "approval";
                                default -> "auto";
                            });
                            view.put("allowedRoles", List.of());
                            view.put("permissions", null);
                            view.put("riskLevel", tool.riskLevel());
                            view.put("riskStrategy", RiskLevel.strategy(tool.riskLevel()));
                            // What a revocation of this tool's result would be, read off what the tool writes:
                            // a tool that writes a row is undone locally by soft-deleting it, and one that
                            // writes nothing has nothing to revoke.
                            //
                            // 撤销这个工具的结果会是什么，从**它写什么**读出：写下一行的工具靠软删那一行**本地**
                            // 撤销，什么都不写的工具**没有可撤的**。
                            view.put("revokeClass", tool.resultType() == null ? "none" : "local_compensate");
                            return view;
                        }).toList();
                    }

                    @PostMapping("/ai/chat")
                    public Map<String, Object> chat(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject,
                            @RequestBody Map<String, Object> body) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        String message = body.get("message") == null ? "" : String.valueOf(body.get("message"));
                        // The id is this application's to issue. One it never issued starts a new
                        // conversation rather than being adopted, so a caller cannot reach into somebody
                        // else's by naming it (see ConversationStore).
                        ChatTurnService.Turn turn = turns.run(
                                message,
                                body.get("customerId"),
                                body.get("conversationId") == null
                                        ? null : String.valueOf(body.get("conversationId")),
                                principal);

                        Map<String, Object> answer = new LinkedHashMap<>();
                        answer.put("conversationId", turn.conversationId());
                        answer.put("reply", turn.reply().text());
                        answer.put("provider", turn.reply().provider());
                        answer.put("model", turn.reply().model());
                        // The tool this turn actually used. Omitted rather than sent empty when nothing was
                        // routed — the field means "these were called".
                        if (turn.plan() != null) {
                            answer.put("toolCalls", List.of(turn.plan().tool()));
                        }
                        // And that is all of it: the governance facts this answer used to carry —
                        // `status`, `data`, `token`, `effectId`, `error` — are not fields of the frozen
                        // `chat-response`, which declares `additionalProperties: false`. They have their
                        // own surfaces: the tool call's result is reported as events on
                        // `/ai/chat/stream`, and the reply says what the tool answered.
                        //
                        // 而这就是全部了：这条答案过去带的那些治理事实 —— `status`、`data`、`token`、
                        // `effectId`、`error` —— **不是**冻结 `chat-response` 的字段，那个对象写着
                        // `additionalProperties: false`。它们各有自己的面：一次工具调用的结果在
                        // `/ai/chat/stream` 上以**事件**报出，而工具答了什么由 reply 说。
                        return answer;
                    }

                    @PostMapping("/ai/confirmations/{token}")
                    public Map<String, Object> confirm(
                            @PathVariable String token,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject,
                            @RequestBody Map<String, Object> body) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        String decision = String.valueOf(body.getOrDefault("decision", ""));
                        if ("approve".equals(decision)) {
                            return engine.approve(token, principal.userId());
                        }
                        if ("decline".equals(decision) || "reject".equals(decision)) {
                            return engine.decline(token, principal.userId());
                        }
                        // Anything else is not a decision. Answering "declined" for a word nobody
                        // defined would invent a result the caller never asked for -- and, worse,
                        // leave the token pending, so the write it guards stays live.
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "decision must be approve or decline");
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg, pkg);
    }

    private String authController(String pkg) {
        return """
                package %s.web;

                import %s.authz.PermissionAuthorizer;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.LocalIdentities;
                import %s.identity.Principal;
                import cn.com.keelbase.protocol.DelegationToken;
                import java.nio.charset.StandardCharsets;
                import java.security.MessageDigest;
                import java.time.Instant;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * The identity surface: who the caller is, what they may do, and — where this deployment
                 * turns it on — how a person signs in.
                 *
                 * <p>{@code GET /auth/me/permissions} answers in the frozen
                 * {@code permission-capability-list} contract — the same path and the same shape the
                 * runtime and the reference implementation serve, which is what lets one frontend key
                 * page/menu/button visibility off either backend.
                 *
                 * <p>{@code GET /auth/me} and {@code POST /auth/login} are not in the frozen wire
                 * registry: the frozen contracts describe the governance surface, not a deployment's
                 * login page. What fixes their shape is the runtime-neutral frontend that consumes them
                 * — without them the console reaches its own login page and stops, because it restores
                 * a session through {@code /auth/me} and signs in through {@code /auth/login}.
                 *
                 * <p><b>Login issues this application's own session as the token this application
                 * already verifies.</b> The session is a KeelBase delegation token — the frozen §3
                 * shape — signed with the same secret and audience {@link IdentityResolver} checks, so
                 * it travels the one verification path that exists and nothing downstream learns a
                 * second token format. The minted token carries no role, which is the contract's own
                 * property: the role still comes from {@link LocalIdentities} after mapping, so a
                 * session cannot grant authority.
                 *
                 * <p><b>Login is off unless this deployment configures a demo password</b>
                 * ({@code keelbase.demo.password}, injected by the environment — deliberately not
                 * written into this file, and with no default in {@code application.properties}): an
                 * application that shipped a passphrase would be born with a login nobody asked for.
                 * This is tier-A furniture — declared identities and one shared passphrase, which is a
                 * demo and not people — and it adds no rate limit of its own; a deployment with real
                 * users replaces the identity source (the {@link IdentityResolver} SPI) and brings its
                 * own login, rather than hardening this one. It signs in the identities
                 * {@link LocalIdentities} declares, not whatever a replaced resolver answers — which is
                 * the same limit seen from the other side.
                 *
                 * <p>身份面：调用者是谁、能做什么，以及——在本部署把它打开时——一个人怎么登录。
                 *
                 * <p>{@code /auth/me/permissions} 按冻结的 {@code permission-capability-list} 契约作答——
                 * 路径与形状都与运行时和参照实现一致，这正是「一个前端能对着任一后端按页 / 菜单 / 按钮
                 * 控可见性」的原因。
                 *
                 * <p>{@code /auth/me} 与 {@code /auth/login} <b>不在</b>冻结的 wire 登记表里：冻结契约
                 * 描述的是治理面，不是某个部署的登录页家具。定它们形状的是消费它们的<b>运行时中立前端</b>
                 * ——没有这两个端点，控制台会走到它自己的登录页就停下，因为它靠 {@code /auth/me} 恢复会话、
                 * 靠 {@code /auth/login} 登录。
                 *
                 * <p><b>登录签发的是本应用自己的会话，用的就是本应用已经在验的那枚令牌。</b>会话是一枚
                 * KeelBase 委托令牌——冻结的 §3 形状——用与 {@link IdentityResolver} 校验时<b>同一个</b>
                 * secret 与 audience 签名，于是它走的是<b>唯一那条</b>验证路径，下游不必再认第二种令牌格式。
                 * 铸出的令牌<b>不带角色</b>，这正是契约自身的性质：角色仍在映射之后来自
                 * {@link LocalIdentities}，所以会话<b>不能</b>授予权限。
                 *
                 * <p><b>本部署不配置演示口令，登录就是关的</b>（{@code keelbase.demo.password}，由环境
                 * 注入——刻意不写进本文件，{@code application.properties} 里也<b>不给默认值</b>）：一个
                 * 自带口令的应用，等于一出生就带着一个没人要的登录。这是 <b>tier-A 的家具</b>——声明式身份
                 * 加一个共享口令，那是演示、不是给人用的——它<b>自己不设速率限制</b>；有真实用户的部署应当
                 * <b>替换身份源</b>（{@link IdentityResolver} SPI）并自带登录，而不是来加固这一个。它签入的
                 * 是 {@link LocalIdentities} 声明的那些身份，而不是被替换过的解析器会答的——这是同一个限制
                 * 从另一面看的样子。
                 */
                @RestController
                public class AuthController {

                    /**
                     * How long a minted session lasts. Long enough for a demo to be watched, short
                     * enough that a leaked token stops mattering. This application issues no refresh
                     * token: the console re-authenticates when this expires, which is the honest
                     * behaviour for a single-credential login.
                     *
                     * <p>铸出的会话活多久。够一场演示被看完，又短到泄出的令牌很快不再要紧。本应用
                     * <b>不签发刷新令牌</b>：过期后控制台重新登录——对「单凭证登录」来说，这才是诚实的
                     * 行为。
                     */
                    private static final long SESSION_TTL_SECONDS = 8 * 3600;

                    private final IdentityResolver identities;
                    private final PermissionAuthorizer authorizer;
                    private final LocalIdentities directory;
                    private final String delegationSecret;
                    private final String delegationAudience;
                    private final String demoPassword;

                    public AuthController(IdentityResolver identities, PermissionAuthorizer authorizer,
                            LocalIdentities directory,
                            @Value("${keelbase.delegation.secret}") String delegationSecret,
                            @Value("${keelbase.delegation.audience}") String delegationAudience,
                            @Value("${keelbase.demo.password:}") String demoPassword) {
                        this.identities = identities;
                        this.authorizer = authorizer;
                        this.directory = directory;
                        this.delegationSecret = delegationSecret;
                        this.delegationAudience = delegationAudience;
                        this.demoPassword = demoPassword;
                    }

                    @GetMapping("/auth/me/permissions")
                    public Map<String, Object> myPermissions(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        return authorizer.describe(principal(authorization, userId, role, oidcSubject)).toWire();
                    }

                    /**
                     * Who the caller is, as this deployment knows them.
                     *
                     * <p>The console asks this once, at the session point, and keys the whole shell off
                     * the answer. It answers with {@code username} and {@code role} because those are
                     * what this deployment's identity directory holds — tier A is declared identities,
                     * no user table. The console's wider user shape also names a numeric id and an
                     * email; neither exists here, and manufacturing one would hand out an identifier no
                     * other endpoint accepts.
                     *
                     * <p>调用者是谁，按本部署认识的那个身份来答。控制台在会话点上问一次，之后整个外壳都
                     * 按这个答案走。它答 {@code username} 与 {@code role}，因为本部署的身份目录里就这两样
                     * ——tier A 是声明式身份、没有用户表。控制台更宽的用户形状里还带一个数字 id 与一个邮箱；
                     * 这里两者都不存在，硬造一个等于发一个别的端点都不认的标识符。
                     */
                    @GetMapping("/auth/me")
                    public Map<String, Object> me(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal = principal(authorization, userId, role, oidcSubject);
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("username", principal.userId());
                        body.put("role", principal.role());
                        return body;
                    }

                    /**
                     * Sign in, and mint the session the rest of the surface already understands.
                     *
                     * <p>Every failure answers the same way — one message, one status — so a prober
                     * learns nothing about which user ids this deployment knows. The password compare
                     * runs whether or not the user exists, so the answer does not take a different
                     * shape on the way out either.
                     *
                     * <p>登录，并铸出这个面其余部分<b>已经认得</b>的那枚会话。
                     *
                     * <p>每一种失败都同样作答——同一句话、同一个状态码——于是探测者无从知道本部署认识哪些
                     * 用户 id。口令比较<b>无论用户是否存在都会跑</b>，所以答案在出口处也不呈现不同的形状。
                     */
                    @PostMapping("/auth/login")
                    public Map<String, Object> login(@RequestBody Map<String, String> body) {
                        if (demoPassword == null || demoPassword.isBlank()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "login is not enabled on this deployment");
                        }
                        String username = body.get("username");
                        String password = body.get("password");
                        String subject = username == null ? null : directory.subjectOf(username).orElse(null);
                        LocalIdentities.Entry entry = subject == null
                                ? null
                                : directory.lookup(subject).orElse(null);
                        boolean passwordMatches = password != null && MessageDigest.isEqual(
                                password.getBytes(StandardCharsets.UTF_8),
                                demoPassword.getBytes(StandardCharsets.UTF_8));
                        if (entry == null || !passwordMatches) {
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                    "invalid username or password");
                        }

                        Map<String, Object> claims = new LinkedHashMap<>();
                        claims.put("sub", subject);
                        claims.put("aud", delegationAudience);
                        claims.put("iss", "keelbase");
                        long now = Instant.now().getEpochSecond();
                        String accessToken = DelegationToken.sign(claims, now, now + SESSION_TTL_SECONDS,
                                delegationSecret);

                        Map<String, Object> user = new LinkedHashMap<>();
                        user.put("username", entry.userId());
                        user.put("role", entry.role());

                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("accessToken", accessToken);
                        // No refresh token is issued: an empty one is the honest answer, and the
                        // console skips its refresh dance rather than retrying a rotation that does
                        // not exist here.
                        //
                        // 不签发刷新令牌：空值才是诚实的答案，而控制台会跳过它的刷新流程，而不是反复重试
                        // 一个这里并不存在的轮换。
                        result.put("refreshToken", "");
                        result.put("user", user);
                        return result;
                    }

                    /**
                     * The console's login page calls these two before anyone has signed in, and both
                     * answer a fact about this deployment rather than a placeholder.
                     *
                     * <p>Answering matters as much as the answer: the console is built to swallow a
                     * failure here, so an absent endpoint and an empty one look the same page-side —
                     * while outside they are different things, and a deployment that answers "none"
                     * should not be indistinguishable from one that answers nothing at all. The runtime
                     * serves both for the same reason and with the same reasoning.
                     *
                     * <p>Both are honest rather than stubbed. This application has no provider registry
                     * and no sink for a visit's address, so "no federated providers" and "nothing was
                     * recorded" are true descriptions of it. Claiming either would be the stub.
                     *
                     * <p>控制台在**没有人登录之前**就调这两条，而两条答的都是这个部署的**事实**、不是占位符。
                     *
                     * <p>**作答**与**答什么**同样要紧：控制台在这两处**本就吞掉失败**，于是「端点不存在」与
                     * 「端点答空」在页面那一侧长得一样——而在外面它们是两回事，一个答「没有」的部署不该与一个
                     * 什么都不答的部署**无从分辨**。运行时同样伺服这两条，理由与推理一致。
                     *
                     * <p>两条都是**诚实**、不是桩：本应用既没有 provider 注册表、也没有落访客地址的地方，故
                     * 「无联邦登录」与「什么都没记下」是对它**真实**的描述。声称相反的才是桩。
                     */
                    @GetMapping("/auth/oauth/providers")
                    public Map<String, Object> oauthProviders() {
                        return Map.of("enabledProviders", List.of(), "providers", List.of(),
                                "groups", Map.of());
                    }

                    @PostMapping("/auth/login-stats")
                    public Map<String, Object> loginStats() {
                        return Map.of("ok", false);
                    }

                    private Principal principal(String authorization, String userId, String role,
                            String oidcSubject) {
                        return identities.resolve(
                                IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg);
    }

    // ── the frozen wire surface: envelope (F4) and frontend contract (F5) ───────

    /**
     * Builds the two frozen REST shapes — {@code api-response} for success, {@code error-body} for
     * failure (the frozen contract's {@code schemas/v1}).
     *
     * <p>Both carry the same four keys deliberately: a client reads {@code code} / {@code message} /
     * {@code data} / {@code timestamp} whichever way the request went, and only {@code data} differs
     * in kind — the payload on success, {@code null} on failure.
     */
    private String wireEnvelope(String pkg) {
        return """
                package %s.web;

                import java.time.Instant;
                import java.time.ZoneOffset;
                import java.time.format.DateTimeFormatter;
                import java.util.LinkedHashMap;
                import java.util.Map;

                /** The two frozen REST shapes. Package-private: nothing outside this layer builds them. */
                final class WireEnvelope {

                    /** The wire convention: ISO-8601 UTC, millisecond precision, trailing Z. */
                    private static final DateTimeFormatter TIMESTAMP =
                            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

                    private WireEnvelope() {
                    }

                    /** Success: {@code data} carries the payload; {@code code} is the status sent. */
                    static Map<String, Object> success(int code, Object data) {
                        return build(code, "操作成功", data);
                    }

                    /** Failure: {@code data} is always null, so "no payload" stays distinguishable. */
                    static Map<String, Object> error(int code, String message) {
                        return build(code, message, null);
                    }

                    /** Key order follows the reference; the contract does not constrain it. */
                    private static Map<String, Object> build(int code, String message, Object data) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("code", code);
                        body.put("message", message);
                        body.put("data", data);
                        body.put("timestamp", TIMESTAMP.format(Instant.now()));
                        return body;
                    }
                }
                """.formatted(pkg);
    }

    /**
     * Wraps every successful REST response in the frozen {@code api-response} envelope.
     *
     * <p>This is not cosmetic. The runtime-neutral frontend unwraps responses through one adapter
     * that expects {@code data} to be present, so an artifact answering with a bare body breaks it on
     * the very first hop — which is the state every endpoint of this artifact was in before this
     * class existed.
     *
     * <p>Failures are exempt, and the exemption is decided by <em>who produced the body</em> rather
     * than by inspecting it: {@code WireErrorController} and {@code WireExceptionHandler} already
     * write the {@code error-body} shape, and wrapping those would nest one envelope inside another.
     */
    private String apiResponseAdvice(String pkg) {
        return """
                package %s.web;

                import org.springframework.core.MethodParameter;
                import org.springframework.http.MediaType;
                import org.springframework.http.converter.HttpMessageConverter;
                import org.springframework.http.server.ServerHttpRequest;
                import org.springframework.http.server.ServerHttpResponse;
                import org.springframework.http.server.ServletServerHttpResponse;
                import org.springframework.web.bind.annotation.ControllerAdvice;
                import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

                @ControllerAdvice
                public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

                    @Override
                    public boolean supports(MethodParameter returnType,
                                            Class<? extends HttpMessageConverter<?>> converterType) {
                        Class<?> producer = returnType.getContainingClass();
                        return !WireErrorController.class.equals(producer)
                                && !WireExceptionHandler.class.equals(producer);
                    }

                    @Override
                    public Object beforeBodyWrite(Object body,
                                                  MethodParameter returnType,
                                                  MediaType selectedContentType,
                                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                                  ServerHttpRequest request,
                                                  ServerHttpResponse response) {
                        // A String return is written by StringHttpMessageConverter, which cannot
                        // serialize an object — wrapping it would turn a working endpoint into a 500.
                        if (body instanceof String) {
                            return body;
                        }
                        return WireEnvelope.success(statusOf(response), body);
                    }

                    /** The status the response already carries, rather than an assumed 200. */
                    private int statusOf(ServerHttpResponse response) {
                        if (response instanceof ServletServerHttpResponse servlet) {
                            int current = servlet.getServletResponse().getStatus();
                            if (current > 0) {
                                return current;
                            }
                        }
                        return 200;
                    }
                }
                """.formatted(pkg);
    }

    /**
     * Renders <em>container-level</em> failures in the frozen {@code error-body} shape: the 404 for a
     * path no controller claims, and anything else the container itself reports.
     *
     * <p>By default Spring Boot answers those with its own {@code {timestamp, status, error, path}}
     * body, which is not the wire contract. The frontend reads {@code message} / {@code reason} /
     * {@code impact} / {@code nextStep} off an error body, so a 404 has to arrive in the same four-key
     * shape as a success.
     *
     * <p>Declaring an {@code ErrorController} bean makes Boot's own back off. Errors this artifact
     * raises itself are shaped by {@code WireExceptionHandler} instead — a thrown exception never
     * reaches the container.
     *
     * <p>Server faults stay deliberately vague: the status is honest, the message is not, because a
     * 5xx message is the classic place internal detail leaks out.
     */
    private String wireErrorController(String pkg) {
        return """
                package %s.web;

                import jakarta.servlet.RequestDispatcher;
                import jakarta.servlet.http.HttpServletRequest;
                import java.util.Map;
                import org.springframework.boot.webmvc.error.ErrorController;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class WireErrorController implements ErrorController {

                    @RequestMapping("/error")
                    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
                        HttpStatus status = statusOf(request);
                        return ResponseEntity.status(status)
                                .body(WireEnvelope.error(status.value(), messageFor(status)));
                    }

                    private HttpStatus statusOf(HttpServletRequest request) {
                        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                        if (attribute instanceof Integer code) {
                            HttpStatus resolved = HttpStatus.resolve(code);
                            if (resolved != null) {
                                return resolved;
                            }
                        }
                        return HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    private String messageFor(HttpStatus status) {
                        return switch (status) {
                            case UNAUTHORIZED -> "authentication required";
                            case FORBIDDEN -> "forbidden";
                            case NOT_FOUND -> "Not found";
                            default -> status.is5xxServerError() ? "服务器内部错误" : status.getReasonPhrase();
                        };
                    }
                }
                """.formatted(pkg);
    }

    /**
     * Renders the errors this artifact <em>raises itself</em> in the frozen {@code error-body} shape.
     *
     * <p>Why this is separate from {@code WireErrorController}: the identity and ownership checks
     * throw ({@code ResponseStatusException}), and a thrown exception is resolved inside the
     * dispatcher — it never reaches the container, so the container-level error controller never sees
     * it. Without this handler those responses would carry Spring's own problem-detail body instead of
     * the wire contract, and the frontend's error path would break on exactly the answers it most
     * needs to explain: 401 and 403.
     */
    private String wireExceptionHandler(String pkg) {
        return """
                package %s.web;

                import java.util.Map;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.server.ResponseStatusException;

                @RestControllerAdvice
                public class WireExceptionHandler {

                    @ExceptionHandler(ResponseStatusException.class)
                    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException exception) {
                        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
                        if (status == null) {
                            status = HttpStatus.INTERNAL_SERVER_ERROR;
                        }
                        String reason = exception.getReason() != null
                                ? exception.getReason()
                                : status.getReasonPhrase();
                        return ResponseEntity.status(status).body(WireEnvelope.error(status.value(), reason));
                    }
                }
                """.formatted(pkg);
    }

    /**
     * The F5 endpoints: what this application is, and which capabilities it exposes.
     *
     * <p>Together with the envelope these are what lets <em>one</em> frontend run against either
     * backend — the frontend branches on capability, never on which runtime answered. Both are
     * unauthenticated by design, because the frontend has to learn what this system offers before
     * anyone holds a token.
     *
     * <p>The {@code /api/v1} prefix is not written here: it comes from {@code server.servlet.context-path}
     * in the generated {@code application.properties}, which mounts the whole application under the
     * prefix the frontend's API base defaults to. Putting it on this mapping instead would leave every
     * other endpoint at the root, and the frontend's calls would 404.
     *
     * <p>The module label is baked in at generation time: the business request this artifact came from
     * carries no human-facing label for the module, so the entity name stands in and the description is
     * empty. The contract asks for strings, not for particular content.
     */
    private String appInfoController(String pkg, BusinessSpec spec) {
        String moduleId = spec.module();
        // The module's own label. This used to be the first entity's name, which is a label for that
        // entity rather than for the module — and one that stops being true as soon as the module holds
        // a second entity (see BusinessSpec.moduleLabel).
        String moduleLabel = spec.moduleLabel();
        return """
                package %s.web;

                import %s.ai.AiTool;
                import %s.ai.ToolRegistry;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/app")
                public class AppInfoController {

                    private static final String MODULE_ID = "%s";
                    private static final String MODULE_LABEL = "%s";

                    private final ToolRegistry tools;

                    public AppInfoController(ToolRegistry tools) {
                        this.tools = tools;
                    }

                    /** {@code data} of {@code GET /app/capabilities}. */
                    @GetMapping("/capabilities")
                    public Map<String, Object> capabilities() {
                        Map<String, Object> ai = new LinkedHashMap<>();
                        ai.put("enabled", true);
                        // Deterministic by construction: this artifact decides tool calls from the
                        // request, it does not call a model. Saying so is the point of the field — the
                        // frontend distinguishes "AI is off" from "AI is on but unconfigured".
                        ai.put("providerConfigured", false);
                        ai.put("provider", "");

                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("preset", "full");
                        body.put("features", Map.of(MODULE_ID, true));
                        body.put("ai", ai);
                        body.put("businessModules", List.of(module()));
                        return body;
                    }

                    /** {@code data} of {@code GET /app/provenance}. */
                    @GetMapping("/provenance")
                    public Map<String, Object> provenance() {
                        Map<String, Object> runtime = new LinkedHashMap<>();
                        runtime.put("preset", "full");
                        runtime.put("businessModules", List.of(module()));
                        runtime.put("aiToolFingerprint", toolFingerprint());

                        Map<String, Object> source = new LinkedHashMap<>();
                        // This artifact is not produced by the TypeScript generator and carries no
                        // manifest; it says so rather than inventing an origin.
                        source.put("manifestPresent", false);

                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("source", source);
                        body.put("runtime", runtime);
                        return body;
                    }

                    private Map<String, Object> module() {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("id", MODULE_ID);
                        entry.put("label", MODULE_LABEL);
                        entry.put("description", "");
                        return entry;
                    }

                    /**
                     * Counts only — never the tools' arguments or risk levels, which stay server-side.
                     *
                     * <p>Read/write is derived from the risk level (R1 = read, anything else = write),
                     * the convention this artifact's generated tool pair follows. The runtime derives it
                     * from a declared result type instead; the generated tool interface carries no such
                     * declaration, so the derivation differs while the shape and the meaning of the
                     * counts do not.
                     */
                    private Map<String, Object> toolFingerprint() {
                        List<AiTool> all = tools.all();
                        long writes = all.stream().filter(tool -> !"R1".equals(tool.riskLevel())).count();
                        Map<String, Object> fingerprint = new LinkedHashMap<>();
                        fingerprint.put("total", all.size());
                        fingerprint.put("readTools", all.size() - writes);
                        fingerprint.put("writeTools", writes);
                        return fingerprint;
                    }
                }
                """.formatted(pkg, pkg, pkg, moduleId, moduleLabel);
    }

    private String governanceController(String pkg, BusinessSpec spec) {
        // The row this application's write tool creates — the target an effect refers to. The generator
        // has always named the detail entity (as `toolClass` does); what is new is that the effect
        // surface looks that row up, so the console's `targetTitle` and `targetSoftDeleted` are read
        // from the row rather than invented.
        EntitySpec detail = spec.entities().size() > 1 ? spec.entities().get(1) : spec.entities().get(0);
        String resultType = spec.tools().stream()
                .map(tool -> tool.resultType())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        // The only human-facing label a row of this shape has is its note; an entity without one has no
        // title to show, and a made-up one would be worse than an empty column.
        String titleLine = detail.fields().stream().anyMatch(f -> f.name().equals("note"))
                ? "        view.put(\"targetTitle\", target.map(row -> row.getNote()).orElse(null));"
                : "        view.put(\"targetTitle\", null);";
        return """
                package %s.web;

                import %s.ai.AuditChainStore;
                import %s.ai.SideEffectStore;
                import %s.domain.%s;
                import %s.domain.%sRepository;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.ZoneOffset;
                import java.time.format.DateTimeParseException;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * The governance surface: the recorded side effects of AI writes, the revoke path, and
                 * the audit chain.
                 *
                 * <p>The effect list answers the shape the runtime-neutral console reads — an envelope
                 * ({@code total}, {@code page}, {@code limit}, {@code items}) whose items carry the
                 * fields that console's model requires. A bare array is one it can neither page nor
                 * render, which is why the console's own end-to-end spec is the thing that decides
                 * whether this is right.
                 */
                @RestController
                public class GovernanceController {

                    /** The console pages through this list; it cannot ask for the whole table. */
                    private static final int MAX_PAGE_SIZE = 100;

                    /** The reference's own audit query answers 50 rows by default and caps a page at 200. */
                    private static final int MAX_AUDIT_PAGE_SIZE = 200;

                    /** The result type whose rows this application can look up — its write tool's. */
                    private static final String RESULT_TYPE = %s;

                    private final SideEffectStore sideEffects;
                    private final AuditChainStore audit;
                    private final IdentityResolver identities;
                    private final %sRepository %sRepository;

                    public GovernanceController(SideEffectStore sideEffects, AuditChainStore audit,
                                                IdentityResolver identities,
                                                %sRepository %sRepository) {
                        this.sideEffects = sideEffects;
                        this.audit = audit;
                        this.identities = identities;
                        this.%sRepository = %sRepository;
                    }

                    @GetMapping("/ai/tool-effects")
                    public Map<String, Object> effects(
                            @RequestParam(required = false) String userId,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int limit,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal = identities.resolve(IdentityEvidence.ofHeaders(
                                authorization, headerUserId, role, oidcSubject));
                        int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
                        // A non-manager sees their own effects whatever they ask for — the filter is not
                        // theirs to lift. A manager's own narrowing by `userId` rides on top of that.
                        List<SideEffectStore.Effect> visible = sideEffects.all().stream()
                                .filter(effect -> principal.isManager()
                                        || effect.userId().equals(principal.userId()))
                                .filter(effect -> userId == null || effect.userId().equals(userId))
                                .toList();
                        // Long arithmetic: `page` is a caller-supplied number, and an offset computed in
                        // ints wraps negative at the top of the range, which would reach `subList` as a
                        // negative index. Clamped, an out-of-range page is an empty page.
                        int from = (int) Math.min((long) (Math.max(page, 1) - 1) * size, visible.size());
                        int to = Math.min(from + size, visible.size());
                        List<Map<String, Object>> items = visible.subList(from, to).stream()
                                .map(this::view)
                                .toList();
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("total", visible.size());
                        body.put("page", page);
                        body.put("limit", size);
                        body.put("items", items);
                        return body;
                    }

                    @DeleteMapping("/ai/tool-effects/{id}")
                    public Map<String, Object> revoke(
                            @PathVariable Long id,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        // The row-level "is this yours?" check stays in the store, as it does in the
                        // runtime; what changed is that the role it consults is the contract's, not a
                        // string compared here.
                        SideEffectStore.Effect effect = sideEffects.requireOwned(
                                id, principal.userId(), principal.isManager());
                        // Local compensation, performed rather than announced: the row this effect
                        // created is soft-deleted. That is what makes `local_compensate` an honest class
                        // for it — an effect labelled compensable that compensates nothing would offer the
                        // console a button that only flips a flag.
                        target(effect).ifPresent(row -> {
                            row.setDeletedAt(Instant.now());
                            %sRepository.save(row);
                        });
                        sideEffects.setRevoked(id);
                        // A revocation leaves a line of its own, as every other governance transition in
                        // this application does: the contract froze `effect_revoke` for exactly this act,
                        // and the note that added it says what it closed — a status that moved with no AI
                        // audit line at all. Written after the row moves, so a revoke that throws on the
                        // way (an unwritable target, say) leaves no line claiming it happened.
                        //
                        // 撤销在链上留一行自己的记录，与这个应用里其它每一次治理迁移一样：契约**为这一
                        // 动作**冻结了 `effect_revoke`，而加上它的那句说明写明了它堵的是什么——状态动了、
                        // 却一条 AI 审计行都没有。这一行写在行移动**之后**，故中途抛异常（比如目标写不
                        // 进去）不会留下「声称撤了」的行。
                        audit.append("effect_revoke", principal.userId(),
                                effect.toolName() + " revoked (effect " + id + ")",
                                false, "revoked", effect.toolName(), effect.conversationId());
                        // The console reads the outcome off `revokeStatus`, as the runtime's answer
                        // does, and that stays. `revoked` is added because the frozen `revokeResult`
                        // requires it — a consumer holding that object should not have to derive a
                        // required field from another.
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("effectId", effect.id());
                        out.put("resultType", effect.resultType());
                        out.put("revokeClass", effect.revokeClass());
                        out.put("revokeStatus", "revoked");
                        out.put("revoked", true);
                        return out;
                    }

                    /** One effect, as the console's model requires it: every field present. */
                    private Map<String, Object> view(SideEffectStore.Effect effect) {
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("id", effect.id());
                        view.put("toolName", effect.toolName());
                        // The conversation that proposed the write. It is carried from the proposal to the
                        // decision on the pending record, so an approval arriving in a request of its own
                        // still knows one; null only when the proposal named no conversation (a task run).
                        //
                        // 提议这次写的那条会话。它从**提议时刻**经待决记录被带到**裁决时刻**，故一个自己到达
                        // 的批准仍知道它；只有提议没有点名会话（一次任务运行）时才为 null。
                        view.put("conversationId", effect.conversationId());
                        view.put("resultType", effect.resultType());
                        view.put("resultId", effect.resultId());
                        view.put("argsHash", effect.argsHash());
                        view.put("createdAt", effect.createdAt().toString());
                        Optional<%s> target = target(effect);
                        view.put("targetExists", target.isPresent());
                        view.put("targetSoftDeleted", target.map(row -> row.getDeletedAt() != null).orElse(false));
                %s
                        view.put("revokeClass", effect.revokeClass());
                        // One stored field feeds two contract fields, and the contract gives the two
                        // different vocabularies — `status` says what happened to the effect, `revokeStatus`
                        // says where the revoke is (null while none was ever attempted). The stored values
                        // map onto them without a guess: `executed` means no revoke has been tried, so the
                        // revoke field is null; `revoked` means both.
                        //
                        // 一条存储字段喂着契约的**两个**字段，而两者词表不同 —— `status` 说 effect 怎么了，
                        // `revokeStatus` 说撤销走到哪（**从未撤销过则为 null**）。存进来的取值不需要猜就能对上：
                        // `executed` ＝ 还没试过撤销 ⇒ 撤销字段为 null；`revoked` ＝ 两者都是。
                        String stored = effect.revokeStatus();
                        view.put("status", "revoked".equals(stored) ? "revoked" : "executed");
                        view.put("revokeStatus", "executed".equals(stored) ? null : stored);

                        // The frozen `traceItem`'s remaining three, answered with what this application has:
                        // its own `afterSnapshot` — what the tool produced, captured when the write ran —
                        // and nulls where it has no concept to offer. `beforeSnapshot` is null because
                        // nothing here overwrites an existing row, so there is no before to record;
                        // `compensationGroup` and `parentEffectId` are null because it has neither a
                        // compensation group nor a parent/child relation between effects, and inventing
                        // one would be a claim with nothing behind it.
                        //
                        // 冻结 `traceItem` 余下的三样，按本应用**有的**答：自己的 `afterSnapshot` —— 工具产出了
                        // 什么、在写跑成时捕获 —— 以及**没有对应概念**处的 null。`beforeSnapshot` 为 null，是因为
                        // 本应用**不改写既有行**、没有「之前」可记；`compensationGroup` 与 `parentEffectId` 为
                        // null，是因为它既没有补偿组、也没有 effect 之间的父子关系，编一个出来只是**没有任何
                        // 东西支撑的主张**。
                        view.put("beforeSnapshot", null);
                        view.put("afterSnapshot", effect.afterSnapshot());
                        view.put("compensationGroup", null);
                        view.put("parentEffectId", null);

                        // What the console renders the revoke button on, decided here rather than
                        // re-derived there — the rule the runtime applies too.
                        view.put("revocable",
                                !"none".equals(effect.revokeClass()) && "executed".equals(effect.revokeStatus()));
                        return view;
                    }

                    /**
                     * The row this effect created, when it created one here and that row is still there.
                     * A soft-deleted row is still found on purpose: `targetExists` and
                     * `targetSoftDeleted` are two answers, not one.
                     */
                    private Optional<%s> target(SideEffectStore.Effect effect) {
                        return java.util.Objects.equals(RESULT_TYPE, effect.resultType())
                                        && effect.resultId() != null
                                ? %sRepository.findById(effect.resultId())
                                : Optional.empty();
                    }

                    /**
                     * The rows on the chain, filtered by caller and by period — the read surface the
                     * chain did not have, in the frozen {@code ai-audit-log-row} shape.
                     *
                     * <p><b>Administrators only, as the chain's own verdict is.</b> Both are the chain's
                     * state — the verdict says whether it holds, these say what it holds — and the
                     * console asks for either from a page it shows only to an administrator. The
                     * reference's own query carries the same gate, and a chain readable by everyone is
                     * not an audit trail, it is a roster.
                     *
                     * <p>The reference also filters on agent and organisation identity and on an
                     * authorization-verdict view, over columns this application does not record. A filter
                     * that is accepted and not applied would answer with a list that looks filtered and is
                     * not — nothing downstream could tell — so those are refused rather than ignored.
                     *
                     * 链上的那些行，按调用者和一段时间筛选 —— 这是链**此前没有的读面**，按冻结的
                     * {@code ai-audit-log-row} 形状。
                     *
                     * <p><b>只给管理员，链自身的结论也一样。</b> 两者都是**链的状态** —— 结论说它站不站得住，
                     * 这些说它上面有什么 —— 而控制台是在**只有管理员看得到**的页面上问其中任何一个的。参照实现
                     * 自己那条查询也带同一道闸，而一条**人人可读**的链不是审计轨，是**花名册**。
                     *
                     * <p>参照实现还按 agent 与组织身份、以及一个授权结论视图筛选 —— 那些列本应用**一个都不记**。
                     * 一个**被接受却没被施加**的筛选，回出来的是一份**看着像筛过、其实没有**的列表、下游分不出 ——
                     * 所以那三个是**被拒绝**、不是被忽略。
                     */
                    @GetMapping("/audit/logs")
                    public List<Map<String, Object>> logs(
                            @RequestParam(required = false) String userId,
                            @RequestParam(required = false) String since,
                            @RequestParam(required = false) String isError,
                            @RequestParam(required = false) String agentId,
                            @RequestParam(required = false) String orgId,
                            @RequestParam(required = false) String denied,
                            @RequestParam(defaultValue = "50") int limit,
                            @RequestParam(defaultValue = "0") int offset,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal = identities.resolve(IdentityEvidence.ofHeaders(
                                authorization, headerUserId, role, oidcSubject));
                        if (!principal.isManager()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "the audit chain is readable by an administrator");
                        }
                        if (agentId != null || orgId != null || denied != null) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "this application records no agent identity, organisation or "
                                            + "authorisation verdict on an audit row, so it cannot answer "
                                            + "those filters");
                        }
                        Boolean errorsOnly = outcome(isError);
                        Instant from = since == null ? null : since(since);
                        List<AuditChainStore.Row> matched = audit.rows().stream()
                                .filter(row -> userId == null || row.userId().equals(userId))
                                .filter(row -> from == null || !row.createdAt().isBefore(from))
                                .filter(row -> errorsOnly == null || row.isError() == errorsOnly)
                                .toList();
                        // Newest first, which is how the runtime's own listing orders the same rows.
                        List<Map<String, Object>> newestFirst = new ArrayList<>();
                        for (int i = matched.size() - 1; i >= 0; i--) {
                            newestFirst.add(auditRow(matched.get(i)));
                        }
                        // Long arithmetic: `offset` is a caller-supplied number, and clamped an
                        // out-of-range window is an empty one rather than a negative index.
                        int start = (int) Math.min((long) Math.max(offset, 0), newestFirst.size());
                        int end = Math.min(start + Math.min(Math.max(limit, 1), MAX_AUDIT_PAGE_SIZE),
                                newestFirst.size());
                        return newestFirst.subList(start, end);
                    }

                    /**
                     * One audit row in the frozen {@code ai-audit-log-row} shape — the fields this
                     * application has, and only those ({@code additionalProperties} is false, so a
                     * guess would be a violation rather than a courtesy).
                     *
                     * 一行审计记录，按冻结的 {@code ai-audit-log-row} 形状 —— 本应用**有的字段**，也只有那些
                     * （{@code additionalProperties} 是 false，故**猜一个**是违规、不是好意）。
                     */
                    private static Map<String, Object> auditRow(AuditChainStore.Row row) {
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("id", row.id());
                        view.put("userId", row.userId());
                        view.put("action", row.action());
                        view.put("detail", row.detail());
                        view.put("isError", row.isError());
                        view.put("createdAt", row.createdAt().toString());
                        // C-3: the outcome and the tool name, the two off-chain columns. A row that is
                        // not about one tool carries null there rather than an invented name.
                        //
                        // C-3：结局与工具名，链外两列。不是关于某一个工具的行是 null，而不是编出来的名字。
                        view.put("outcome", row.outcome());
                        view.put("tool", row.tool());
                        return view;
                    }

                    /**
                     * The reference reads this filter as the two words, so anything else is a request
                     * this surface cannot answer rather than one that quietly means "false".
                     *
                     * 参照实现把这个筛选读成那两种取值，故其它任何输入都是**这条面答不了**的请求，而不是一个
                     * **悄悄等于 false** 的请求。
                     */
                    private static Boolean outcome(String isError) {
                        if (isError == null) {
                            return null;
                        }
                        if (!"true".equals(isError) && !"false".equals(isError)) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "isError is `true` or `false`");
                        }
                        return Boolean.valueOf(isError);
                    }

                    /**
                     * The reference reads this with {@code new Date(...)}, which takes a date and a
                     * date-time alike; a deployment off by a day is worse than one that says so.
                     *
                     * 参照实现用 {@code new Date(...)} 解析它，日期与日期时间都收；一个**差了一天**的部署比一个
                     * **说明自己读不懂输入**的部署更糟。
                     */
                    private static Instant since(String text) {
                        try {
                            return Instant.parse(text);
                        } catch (DateTimeParseException notAnInstant) {
                            try {
                                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
                            } catch (DateTimeParseException notADateEither) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "since must be an ISO-8601 instant or date, such as 2026-10-09 "
                                                + "or 2026-10-09T00:00:00Z");
                            }
                        }
                    }

                    /**
                     * The chain's state. Administrators only — the same gate the runtime puts on this
                     * route, and the one the object's own implementation carries.
                     *
                     * <p>Resolving the caller is not the whole gate, and it never was: it answers "is
                     * there a caller this application knows", which is a different question from "may
                     * this caller see the chain's state". The console asks for it from a page it shows
                     * only to an administrator.
                     *
                     * 链自身的状态。**只给管理员** —— 与运行时守这条路由的是同一道闸，也是这个对象自己的实现
                     * 带的那道。
                     *
                     * <p>**解析调用方不是全部的门**，从来都不是：它答的是「有没有一个本应用认识的调用者」，
                     * 而那是与「这个调用方可不可以看到链的状态」**不同**的一个问题。控制台是在**只有管理员
                     * 看得到**的页面上问它的。
                     */
                    @GetMapping("/audit/verify")
                    public Map<String, Object> verify(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal = identities.resolve(
                                IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        if (!principal.isManager()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "the audit chain is readable by an administrator");
                        }
                        // The walk's own answer **and the rows it walked**: the frozen object's `chain` is
                        // required, and a chain that does not report its rows is one nobody can check by
                        // hand. `broken` is left out rather than guessed at — it marks *which* row the walk
                        // broke on, and this store's walk answers yes-or-no rather than where; the schema
                        // makes the field optional, so its absence is honest rather than a silence.
                        //
                        // 这次走自己的答案，**以及它走过的那些行**：冻结对象的 `chain` 是**必填**，而一条**不报行**的链
                        // 是**没人能手工查**的链。`broken` 是**留空**、不是猜的 —— 它标的是**断在哪一行**，而这个存储的
                        // 走法只答**通不通**、不答**在哪**；schema 里它是可选的，故缺席是**诚实**、不是沉默。
                        List<Map<String, Object>> chain = new ArrayList<>();
                        for (AuditChainStore.Row row : audit.rows()) {
                            Map<String, Object> node = new LinkedHashMap<>();
                            node.put("id", row.id());
                            node.put("createdAt", row.createdAt().toString());
                            node.put("action", row.action());
                            node.put("prevHash", row.prevHash());
                            node.put("hash", row.hash());
                            node.put("isError", row.isError());
                            chain.add(node);
                        }
                        Map<String, Object> answer = new LinkedHashMap<>();
                        answer.put("valid", audit.verify());
                        answer.put("checked", audit.size());
                        answer.put("chain", chain);
                        return answer;
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, detail.name(), pkg, detail.name(), pkg, pkg, pkg,
                resultType == null ? "null" : "\"" + resultType + "\"",
                detail.name(), decap(detail.name()), detail.name(), decap(detail.name()), decap(detail.name()),
                decap(detail.name()), decap(detail.name()), detail.name(), titleLine, detail.name(),
                decap(detail.name()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * A CRUD controller for one entity — one of these is emitted per entity in the spec. Which actions
     * a caller may take, and whether the rows
     * are narrowed to the ones they own, comes from the authorization contracts — the list and update
     * paths ask {@link OwnershipGuard} and act on the answer, rather than comparing a role string here.
     */
    private String entityController(String pkg, EntitySpec entity, BusinessSpec spec) {
        String name = entity.name();
        String plural = table(name);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".web;\n\n");
        sb.append("import ").append(pkg).append(".authz.OwnershipGuard;\n");
        sb.append("import ").append(pkg).append(".domain.").append(name).append(";\n");
        sb.append("import ").append(pkg).append(".domain.").append(name).append("Repository;\n");
        sb.append("import ").append(pkg).append(".identity.IdentityEvidence;\n");
        sb.append("import ").append(pkg).append(".identity.IdentityResolver;\n");
        sb.append("import ").append(pkg).append(".identity.Principal;\n");
        sb.append("import java.util.List;\nimport java.util.Objects;\n");
        sb.append("import org.springframework.http.HttpStatus;\n");
        sb.append("import org.springframework.web.bind.annotation.GetMapping;\n");
        sb.append("import org.springframework.web.bind.annotation.PatchMapping;\n");
        sb.append("import org.springframework.web.bind.annotation.PostMapping;\n");
        sb.append("import org.springframework.web.bind.annotation.PathVariable;\n");
        sb.append("import org.springframework.web.bind.annotation.RequestBody;\n");
        sb.append("import org.springframework.web.bind.annotation.RequestHeader;\n");
        sb.append("import org.springframework.web.bind.annotation.RestController;\n");
        sb.append("import org.springframework.web.server.ResponseStatusException;\n\n");
        sb.append("@RestController\npublic class ").append(name).append("Controller {\n\n");
        sb.append("    private final ").append(name).append("Repository repository;\n");
        sb.append("    private final IdentityResolver identities;\n");
        sb.append("    private final OwnershipGuard ownership;\n\n");
        sb.append("    public ").append(name).append("Controller(").append(name).append("Repository repository,\n");
        sb.append("            IdentityResolver identities, OwnershipGuard ownership) {\n");
        sb.append("        this.repository = repository;\n");
        sb.append("        this.identities = identities;\n");
        sb.append("        this.ownership = ownership;\n    }\n\n");

        sb.append("    @PostMapping(\"/").append(plural).append("\")\n");
        sb.append("    public ").append(name).append(" create(@RequestBody ").append(name).append(" body,\n");
        sb.append(identityHeaders());
        sb.append("        Principal principal =\n");
        sb.append("                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));\n");
        sb.append("        ownership.requireAction(principal, \"").append(name).append("\", \"create\");\n");
        sb.append("        body.setOwnerUserId(principal.userId());\n");
        sb.append("        return repository.save(body);\n    }\n\n");

        sb.append("    @GetMapping(\"/").append(plural).append("\")\n");
        sb.append("    public List<").append(name).append("> list(\n");
        sb.append(identityHeaders());
        sb.append("        Principal principal =\n");
        sb.append("                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));\n");
        sb.append("        ownership.requireAction(principal, \"").append(name).append("\", \"read\");\n");
        sb.append("        List<").append(name).append("> rows = repository.findAll();\n");
        sb.append("        if (!ownership.seesOwnRowsOnly(principal, \"").append(name).append("\")) {\n");
        sb.append("            return rows;\n        }\n");
        sb.append("        return rows.stream()\n");
        sb.append("                .filter(row -> Objects.equals(row.getOwnerUserId(), principal.userId()))\n");
        sb.append("                .toList();\n    }\n\n");

        sb.append("    @PatchMapping(\"/").append(plural).append("/{id}\")\n");
        sb.append("    public ").append(name).append(" update(\n");
        sb.append("            @PathVariable Long id,\n");
        sb.append("            @RequestBody ").append(name).append(" patch,\n");
        sb.append(identityHeaders());
        sb.append("        Principal principal =\n");
        sb.append("                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));\n");
        sb.append("        ").append(name).append(" entity = repository.findById(id)\n");
        sb.append("                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));\n");
        sb.append("        ownership.requireAccess(principal, \"").append(name)
                .append("\", \"update\", entity.getOwnerUserId());\n");
        for (FieldSpec f : entity.fields()) {
            String g = capitalize(f.name());
            sb.append("        if (patch.get").append(g).append("() != null) { entity.set").append(g)
                    .append("(patch.get").append(g).append("()); }\n");
        }
        sb.append("        return repository.save(entity);\n    }\n\n");
        sb.append(userCodeBlock());
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * The carriers every governed endpoint reads before resolving a principal: the delegation token the
     * default adapter verifies, and the headers a deployment behind a guard may hand over instead. All of
     * them are optional at this level — which carrier is required is the resolver's decision.
     */
    private static String identityHeaders() {
        return "            @RequestHeader(value = \"Authorization\", required = false) String authorization,\n"
                + "            @RequestHeader(value = \"X-User-Id\", required = false) String userId,\n"
                + "            @RequestHeader(value = \"X-User-Role\", required = false) String role,\n"
                + "            @RequestHeader(value = \"X-Oidc-Sub\", required = false) String oidcSubject) {\n";
    }

    /**
     * A suggested spot for hand-written code. Regeneration no longer keys off these markers — an edit
     * anywhere in the file is merged the same way (see {@link Project}) — so they are advice about
     * where code is least likely to collide with a generated change, not a contract.
     */
    static final String USER_BEGIN = "// <keelbase:user-code>";

    static final String USER_END = "// </keelbase:user-code>";

    /** The empty user-code region as emitted by the generator (content between the markers). */
    static final String USER_EMPTY = "\n    ";

    private static String userCodeBlock() {
        return "    " + USER_BEGIN + USER_EMPTY + USER_END + "\n";
    }

    private static Path write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    static String javaType(String type) {
        return switch (type) {
            case "string", "date" -> "String";
            case "int" -> "Long";
            case "bool" -> "Boolean";
            default -> "String";
        };
    }

    static String table(String entityName) {
        return snake(entityName) + "s";
    }

    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String pascal(String snake) {
        StringBuilder sb = new StringBuilder();
        for (String part : snake.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * A Java string literal for a value that may carry quotes or backslashes.
     *
     * <p>The values interpolated this way are the spec's trigger words and tool names — today plain
     * text — but a generator that pasted them in raw would break on the first request that used a
     * quote, and the breakage would land in the generated project's compile rather than here.
     */
    private static String literal(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ── The decision trace: a conversation's turns and the governance half beside them ───────────

    /**
     * A caller's own conversations, and the trace of one of them — the two faces the console's
     * decision-trace page reads.
     *
     * <p><b>The trace holds both halves.</b> The turns are answered from the conversation; the
     * governance half — the tool calls the turns proposed, the decisions a person took, and the effects
     * those writes produced — is answered from the audit rows and the effect records that carry this
     * conversation's id, and the two are merged into one timeline in the order things happened. A row
     * written with no conversation is genuinely not this conversation's, so it does not appear.
     *
     * 某个调用方自己的会话，以及其中一条的追踪 —— 控制台决策轨迹页读的就是这两条面。
     *
     * <p><b>一条追踪两半都在。</b>回合从**会话自己**答出来；治理那一半 —— 这些回合提议过的工具调用、有人
     * 作过的裁决、以及那些写产出的副作用 —— 从**带着这条会话 id** 的审计行与副作用记录答出来，两半按**事情
     * 发生的先后**并成一条时间线。**没有会话**时写下的行本来不属于这条会话、故不出现。
     */
    private String conversationController(String pkg) {
        return """
                package %s.web;

                import %s.ai.AuditChainStore;
                import %s.ai.DeterministicReplier;
                import %s.ai.SideEffectStore;
                import %s.conversation.ConversationMessage;
                import %s.conversation.ConversationMessageRepository;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import java.time.Instant;
                import java.util.ArrayList;
                import java.util.Comparator;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * A caller's own conversations, and the trace of one of them.
                 *
                 * <p><b>A trace is the conversation's own story, and it holds both halves of it.</b> The
                 * turns come from the conversation; the governance half — the tool calls, the decisions a
                 * person took, and the effects the writes produced — comes from the audit and effect
                 * records that carry this conversation's id, and the two are merged into one timeline in
                 * the order things happened.
                 *
                 * <p><b>What a trace still does not carry.</b> A row written with no conversation (a task
                 * run) is genuinely not this conversation's and does not appear, and neither does the
                 * call's argument string — this application does not store one, and inventing it would
                 * describe a call the row never held. `provider` and `model` come from the default
                 * replier's own constants: they are recorded per reply, not per conversation, so a
                 * deployment that replaced the replier should read them as the default.
                 *
                 * 某个调用方自己的会话，以及其中一条的追踪。
                 *
                 * <p><b>一条追踪是这条会话自己的故事，两半都在。</b>回合来自会话；治理那一半 —— 工具调用、
                 * 有人作过的裁决、以及那些写产出的副作用 —— 来自**带着这条会话 id** 的审计与副作用记录，
                 * 两半按**事情发生的先后**并成一条时间线。
                 *
                 * <p><b>一条追踪仍然不装的东西。</b>**没有会话**时写下的行（一次任务运行）本来就不属于这条
                 * 会话、故不出现；也没有调用的参数串 —— 本应用不存它，而编一个等于**描述一次那一行从未持有过的
                 * 调用**。`provider` 与 `model` 取自**默认 replier 自己的常量**：它们记在**每次回复**上、
                 * 不是每条会话上，故**换过 replier 的部署**应把它们读作默认。
                 */
                @RestController
                public class ConversationController {

                    private final ConversationMessageRepository messages;
                    private final AuditChainStore audit;
                    private final SideEffectStore sideEffects;
                    private final IdentityResolver identities;

                    public ConversationController(ConversationMessageRepository messages, AuditChainStore audit,
                                                  SideEffectStore sideEffects, IdentityResolver identities) {
                        this.messages = messages;
                        this.audit = audit;
                        this.sideEffects = sideEffects;
                        this.identities = identities;
                    }

                    @GetMapping("/ai/conversations")
                    public List<Map<String, Object>> conversations(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal caller =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        List<Conversation> found = new ArrayList<>();
                        for (String id : messages.conversationIdsFor(caller.userId())) {
                            found.add(new Conversation(id, messages.findTop2ByConversationIdOrderByIdAsc(id)));
                        }
                        // Most recent activity first, the order the console lists them in. Sorted here
                        // because a conversation is derived from its messages, so there is no row to
                        // order by.
                        //
                        // 最近有活动的在前 —— 控制台列表就是这个序。排序放在这里，因为会话是**由消息推出来的**，
                        // 没有一行可供排序。
                        found.sort((a, b) -> b.lastActivity().compareTo(a.lastActivity()));
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (Conversation conversation : found) {
                            out.add(conversation.data());
                        }
                        return out;
                    }

                    @GetMapping("/ai/conversations/{id}/trace")
                    public Map<String, Object> trace(
                            @PathVariable String id,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal caller =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        List<ConversationMessage> turns = messages.findByConversationIdOrderByIdAsc(id);
                        if (turns.isEmpty()) {
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown conversation: " + id);
                        }
                        if (!turns.get(0).getUserId().equals(caller.userId())) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "conversation belongs to another user");
                        }
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("conversation", new Conversation(id, turns).data());
                        body.put("steps", steps(id, turns));
                        return body;
                    }

                    /**
                     * The trace as one timeline: the turns, and the governance events those turns produced,
                     * merged by when each happened. Merged rather than concatenated because a trace is a
                     * story in order; each source carries its own timestamp, and a tie is broken by the
                     * order the steps were gathered in, so the result is stable.
                     *
                     * 把追踪并成**一条时间线**：回合，与那些回合产出的治理事件，按各自发生的先后合并。合并、
                     * 而不是拼接，因为一条追踪是**按序的故事**；每个来源自带时间戳，并列时按**收集的先后**
                     * 打破，于是结果是稳定的。
                     */
                    private List<Map<String, Object>> steps(String conversationId,
                                                            List<ConversationMessage> turns) {
                        List<Timed> timeline = new ArrayList<>();
                        for (int i = 0; i < turns.size(); i++) {
                            ConversationMessage turn = turns.get(i);
                            String type;
                            if (ConversationMessage.USER.equals(turn.getRole())) {
                                type = "input";
                            } else if (ConversationMessage.ASSISTANT.equals(turn.getRole())
                                    && turn.getContent() != null && !turn.getContent().isBlank()) {
                                type = "assistant";
                            } else {
                                continue;
                            }
                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("id", "msg-" + i);
                            step.put("type", type);
                            step.put("time", turn.getCreatedAt());
                            step.put("content", turn.getContent());
                            timeline.add(new Timed(turn.getCreatedAt(), timeline.size(), step));
                        }
                        for (AuditChainStore.Row row : audit.rowsFor(conversationId)) {
                            Map<String, Object> step = auditStep(row);
                            if (step != null) {
                                timeline.add(new Timed(row.createdAt(), timeline.size(), step));
                            }
                        }
                        for (SideEffectStore.Effect effect : sideEffects.byConversation(conversationId)) {
                            timeline.add(new Timed(effect.createdAt(), timeline.size(), effectStep(effect)));
                        }
                        timeline.sort(Comparator.comparing(Timed::time).thenComparingInt(Timed::order));
                        return timeline.stream().map(Timed::step).toList();
                    }

                    /**
                     * One audit row as a step: a {@code tool_call} for a call, a {@code confirmation} for a
                     * decision. A row that is neither is left out rather than shown as a step type it is
                     * not. The call's outcome is carried as {@code success}, and a decision carries the
                     * frozen {@code outcome} the row's action maps to.
                     *
                     * 一行审计作为一步：一次调用是 `tool_call`，一次裁决是 `confirmation`。两者都不是的行
                     * **略过**，而不是显示成它并非的那个步类型。调用的结局以 {@code success} 承载，裁决则承载
                     * 它那一行映射到的冻结 {@code outcome}。
                     */
                    private static Map<String, Object> auditStep(AuditChainStore.Row row) {
                        String type;
                        if ("tool_call".equals(row.action())) {
                            type = "tool_call";
                        } else if ("tool_confirmation".equals(row.action())) {
                            type = "confirmation";
                        } else {
                            return null;
                        }
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("id", "audit-" + row.id());
                        step.put("type", type);
                        step.put("time", row.createdAt());
                        if (row.tool() != null) {
                            step.put("toolName", row.tool());
                        }
                        step.put("detail", row.detail());
                        if ("tool_call".equals(type)) {
                            step.put("success", !row.isError());
                        } else {
                            String outcome = confirmationOutcome(row.outcome());
                            if (outcome != null) {
                                step.put("outcome", outcome);
                            }
                        }
                        return step;
                    }

                    /**
                     * The frozen {@code confirmation} step's {@code outcome}, from this application's audit
                     * outcome. The vocabulary is the object's own; an outcome that is none of those is left
                     * off rather than forced into a word that would misdescribe it.
                     *
                     * 冻结 `confirmation` 步的 {@code outcome}，取自本应用的审计结局。词表是对象自己的；不属于
                     * 其中的结局**不填**，而不是硬塞进一个会误述它的词。
                     */
                    private static String confirmationOutcome(String auditOutcome) {
                        if ("approved".equals(auditOutcome)) {
                            return "approve";
                        }
                        if ("declined".equals(auditOutcome)) {
                            return "decline";
                        }
                        return null;
                    }

                    /** One effect as a step, in the frozen {@code traceEffect} shape the object declares. */
                    private static Map<String, Object> effectStep(SideEffectStore.Effect effect) {
                        Map<String, Object> traceEffect = new LinkedHashMap<>();
                        traceEffect.put("effectId", effect.id());
                        traceEffect.put("resultType", effect.resultType());
                        traceEffect.put("resultId", effect.resultId());
                        traceEffect.put("revocable", !"none".equals(effect.revokeClass()));
                        traceEffect.put("after", effect.afterSnapshot());
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("id", "effect-" + effect.id());
                        step.put("type", "effect");
                        step.put("time", effect.createdAt());
                        step.put("effect", traceEffect);
                        return step;
                    }

                    /** A step with the moment it happened and the order it was gathered in, for a stable merge. */
                    private record Timed(Instant time, int order, Map<String, Object> step) {
                    }

                    /** A conversation and the turns that were looked up for it. */
                    private record Conversation(String id, List<ConversationMessage> turns) {

                        Instant lastActivity() {
                            return turns.get(turns.size() - 1).getCreatedAt();
                        }

                        Map<String, Object> data() {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("id", id);
                            data.put("userId", turns.get(0).getUserId());
                            data.put("provider", DeterministicReplier.PROVIDER);
                            data.put("model", DeterministicReplier.MODEL);
                            data.put("summary", null);
                            data.put("createdAt", turns.get(0).getCreatedAt());
                            data.put("lastActivityAt", lastActivity());
                            List<Map<String, Object>> shown = new ArrayList<>();
                            for (ConversationMessage turn : turns) {
                                Map<String, Object> message = new LinkedHashMap<>();
                                message.put("role", turn.getRole());
                                message.put("content", turn.getContent());
                                message.put("timestamp", turn.getCreatedAt());
                                shown.add(message);
                            }
                            data.put("messages", shown);
                            return data;
                        }
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg, pkg, pkg, pkg);
    }

    // ── The operations chain: a second audit, of the business writes themselves ──────────────────

    /**
     * The operations table. A second chain beside the AI one, and its own row shape — the console's
     * operations page reads this one, its decision-trace page the other.
     *
     * 操作链的表。与 AI 那条并排的第二条链、有它自己的行形状 —— 控制台的操作审计页读这条，决策轨迹页读另一条。
     */
    private String operationAuditMigration() {
        return """
                -- The operations audit: what was done to the business, as opposed to what the AI proposed.
                --
                -- A second chain, and that it is a second one is the reason it exists: the AI audit records
                -- what a tool call came to, and this records the business writes themselves — including the
                -- ones no AI was involved in. The console's operations page reads this one.
                --
                -- Not recorded here, deliberately: the request body (there is no PII declaration to redact
                -- by, so a stored body would be a leak), the field-level diff, the feature names a mapping
                -- declares, and the two hash columns (a chained audit needs a second chain head and its own
                -- locking, and doing half of that is worse than doing none).
                --
                -- 操作审计：**对业务做了什么**，与「AI 提议了什么」相对。
                --
                -- 这是**第二条链**，而它是第二条的理由正是它存在的理由：AI 审计记的是「一次工具调用最后怎么了」，
                -- 这条记的是**业务写本身** —— 包括**没有 AI 参与**的那些。控制台的操作审计页读的是这一条。
                --
                -- 这里**刻意不记**：请求正文（没有 PII 声明可打码，存正文等于泄露）、字段级差异、映射声明的
                -- 功能名、以及两个哈希列（链式审计需要第二个链头与它自己的加锁，做一半比不做更糟）。

                CREATE TABLE operation_audit_logs (
                    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    user_id     VARCHAR(255),
                    action      VARCHAR(32) NOT NULL,
                    method      VARCHAR(16) NOT NULL,
                    path        VARCHAR(512) NOT NULL,
                    target_id   VARCHAR(255),
                    status_code INTEGER,
                    ip          VARCHAR(64),
                    user_agent  VARCHAR(512),
                    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
                );
                """;
    }

    /** One business operation: who did it, what they did it with, and how it ended. */
    private String operationAuditLog(String pkg) {
        return """
                package %s.audit;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.GeneratedValue;
                import jakarta.persistence.GenerationType;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import java.time.Instant;

                /**
                 * One business operation: who did it, what they did it with, and how it ended.
                 *
                 * <p>Not hash-chained, and not because that was overlooked: the frozen
                 * {@code operation-audit-log-row} marks {@code prevHash} and {@code hash} optional, and
                 * chaining a second audit means a second chain head with its own locking. Half of that
                 * would be worse than none, so this is the honest subset.
                 *
                 * 一次业务操作：谁做的、用什么做的、结果如何。
                 *
                 * <p>**不做哈希链**，而这**不是**疏忽：冻结的 {@code operation-audit-log-row} 把
                 * {@code prevHash} 与 {@code hash} 标为**可选**，而给第二条审计加链意味着**第二个链头**和它
                 * 自己的加锁。做一半比不做更糟，所以这里的列就是**诚实的子集**。
                 */
                @Entity
                @Table(name = "operation_audit_logs")
                public class OperationAuditLog {

                    @Id
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;

                    /** Null for a request that carried no identity, such as a refused login attempt. */
                    @Column(name = "user_id")
                    private String userId;

                    /** The frozen vocabulary's own words: LOGIN, LOGOUT, UPLOAD, CREATE, UPDATE, DELETE. */
                    @Column(nullable = false, length = 32)
                    private String action;

                    @Column(nullable = false, length = 16)
                    private String method;

                    @Column(nullable = false, length = 512)
                    private String path;

                    /** The row this operation named, read off the route's own template variables. */
                    @Column(name = "target_id")
                    private String targetId;

                    @Column(name = "status_code")
                    private Integer statusCode;

                    /** The direct peer; behind a proxy this is the proxy unless it forwards the header. */
                    @Column(length = 64)
                    private String ip;

                    @Column(name = "user_agent", length = 512)
                    private String userAgent;

                    @Column(name = "created_at", nullable = false)
                    private Instant createdAt = Instant.now();

                    protected OperationAuditLog() {
                    }

                    public OperationAuditLog(String userId, String action, String method, String path,
                                             String targetId, Integer statusCode, String ip, String userAgent) {
                        this.userId = userId;
                        this.action = action;
                        this.method = method;
                        this.path = path;
                        this.targetId = targetId;
                        this.statusCode = statusCode;
                        this.ip = ip;
                        this.userAgent = userAgent;
                    }

                    public Long getId() {
                        return id;
                    }

                    public String getUserId() {
                        return userId;
                    }

                    public String getAction() {
                        return action;
                    }

                    public String getMethod() {
                        return method;
                    }

                    public String getPath() {
                        return path;
                    }

                    public String getTargetId() {
                        return targetId;
                    }

                    public Integer getStatusCode() {
                        return statusCode;
                    }

                    public String getIp() {
                        return ip;
                    }

                    public String getUserAgent() {
                        return userAgent;
                    }

                    public Instant getCreatedAt() {
                        return createdAt;
                    }
                }
                """.formatted(pkg);
    }

    private String operationAuditLogRepository(String pkg) {
        return """
                package %s.audit;

                import org.springframework.data.domain.Page;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.jpa.repository.JpaRepository;

                public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long> {

                    /** Newest first — the order an operations page reads. */
                    Page<OperationAuditLog> findAllByOrderByIdDesc(Pageable pageable);
                }
                """.formatted(pkg);
    }

    /**
     * Records the business writes this application serves. What it records is decided by what a request
     * <em>is</em>, not by who sent it: a write method on a business path. The AI surface is left out —
     * its calls already have a chain of their own — and so is the one read-only POST this application
     * exposes, named rather than matched by a rule broad enough to catch writes too.
     *
     * 记录本应用所服务的业务写。记什么由**这个请求是什么**决定，不由**谁发的**决定：业务路径上的写方法。
     * AI 面被排除 —— 它的调用已经有自己的链 —— 以及本应用暴露的那个**只读 POST**，**逐个点名**、不定一条
     * 宽到也会抓住写的规则。
     */
    private String operationAuditInterceptor(String pkg) {
        return """
                package %s.web;

                import %s.audit.OperationAuditLog;
                import %s.audit.OperationAuditLogRepository;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import jakarta.servlet.http.HttpServletRequest;
                import jakarta.servlet.http.HttpServletResponse;
                import java.util.Locale;
                import java.util.Map;
                import java.util.Set;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.servlet.HandlerInterceptor;
                import org.springframework.web.servlet.HandlerMapping;

                /**
                 * Records the business writes this application serves — the operations chain, as distinct
                 * from the AI one.
                 *
                 * <p>The row is written after the handler has answered, so it can say how the request
                 * ended; the identity has to be read before that, because a request's security context is
                 * gone by then. Failing to write the row does not fail the request: the write already
                 * happened, and refusing afterwards would not put the row back. A request that carried no
                 * identity is still an operation — a refused login attempt — and is recorded with nobody
                 * attached rather than dropped.
                 *
                 * 记录本应用所服务的业务写 —— 这是操作链，与 AI 那条不同。
                 *
                 * <p>那一行写在处理器**答完之后**，所以它说得出这次请求**怎么结束的**；而**身份必须在之前**读，
                 * 因为到那时请求的安全上下文已经没了。**写不进这一行不会让请求失败**：写已经发生了，事后拒绝也
                 * 放不回那一行。**没带身份**的请求**同样是一次操作** —— 被拒的登录尝试 —— 它被记下、只是
                 * **不挂人**，而不是被丢掉。
                 */
                public class OperationAuditInterceptor implements HandlerInterceptor {

                    private static final Logger log = LoggerFactory.getLogger(OperationAuditInterceptor.class);

                    private static final Set<String> WRITE_METHODS = Set.of("POST", "PATCH", "PUT", "DELETE");

                    /** Where preHandle leaves the caller for afterCompletion to pick up. */
                    private static final String OPERATOR = "keelbase.operationAudit.operator";

                    private final OperationAuditLogRepository logs;
                    private final IdentityResolver identities;

                    public OperationAuditInterceptor(OperationAuditLogRepository logs,
                                                     IdentityResolver identities) {
                        this.logs = logs;
                        this.identities = identities;
                    }

                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                             Object handler) {
                        request.setAttribute(OPERATOR, operator(request));
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                                Object handler, Exception failure) {
                        String method = request.getMethod().toUpperCase(Locale.ROOT);
                        String path = request.getRequestURI();
                        if (!isOperation(method, path)) {
                            return;
                        }
                        try {
                            logs.save(new OperationAuditLog(
                                    (String) request.getAttribute(OPERATOR),
                                    action(method, path),
                                    method,
                                    path,
                                    targetId(request),
                                    response.getStatus(),
                                    request.getRemoteAddr(),
                                    request.getHeader("User-Agent")));
                        } catch (RuntimeException notAudited) {
                            log.warn("operation not audited: {} {}", method, path, notAudited);
                        }
                    }

                    private static boolean isOperation(String method, String path) {
                        if (!WRITE_METHODS.contains(method)) {
                            return false;
                        }
                        if (path.contains("/ai/")) {
                            return false;
                        }
                        // The one POST that only reads, named rather than matched by a rule: a rule broad
                        // enough to catch it would catch writes too.
                        //
                        // 唯一一个**只读的 POST**，**逐个点名**、不定规则：宽到能抓住它的规则也会抓住写。
                        return !path.endsWith("/auth/login-stats");
                    }

                    private static String action(String method, String path) {
                        if (path.endsWith("/auth/login")) {
                            return "LOGIN";
                        }
                        if (path.endsWith("/auth/logout")) {
                            return "LOGOUT";
                        }
                        if (path.endsWith("/upload")) {
                            return "UPLOAD";
                        }
                        return switch (method) {
                            case "POST" -> "CREATE";
                            case "PATCH", "PUT" -> "UPDATE";
                            case "DELETE" -> "DELETE";
                            default -> method;
                        };
                    }

                    /** The row the route names, where it names one — off the handler's own templates. */
                    private static String targetId(HttpServletRequest request) {
                        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                        if (variables instanceof Map<?, ?> templates && templates.get("id") != null) {
                            return String.valueOf(templates.get("id"));
                        }
                        return null;
                    }

                    private String operator(HttpServletRequest request) {
                        try {
                            Principal caller = identities.resolve(IdentityEvidence.ofHeaders(
                                    request.getHeader("Authorization"),
                                    request.getHeader("X-User-Id"),
                                    request.getHeader("X-User-Role"),
                                    request.getHeader("X-Oidc-Sub")));
                            return caller == null ? null : caller.userId();
                        } catch (RuntimeException anonymous) {
                            // No identity is still an operation, recorded with nobody attached rather than
                            // dropped — the same boundary the runner-up chain draws.
                            //
                            // **没带身份**同样是一次操作，记下、只是**不挂人**，而不是丢掉 —— 与隔壁那条链
                            // 划的同一条边界。
                            return null;
                        }
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg);
    }

    /**
     * Registers the operations audit in this application — on by default, switchable off with
     * {@code keelbase.operations-audit.enabled=false}.
     *
     * <p><b>Why on by default.</b> The operations page is the reason it exists, and a page that fills in
     * only once somebody remembers to flip a property stays empty. <b>Why it is switchable.</b> It is a
     * request interceptor, so it captures the business writes of this application — and a deployment that
     * already keeps an operations log of its own does not necessarily want a second one.
     *
     * **注册操作审计** —— 默认开，用 `keelbase.operations-audit.enabled=false` 关掉。
     *
     * <p><b>为什么默认开。</b> 操作审计页正是它存在的理由，而**要等人想起来翻开关**才会填的页，是一个
     * **一直空着**的页。<b>为什么可关。</b> 它是一个请求拦截器，所以它捕获的是**本应用**的业务写 ——
     * 而**自己已经有一份操作日志**的部署不一定要第二份。
     */
    private String operationsAuditConfiguration(String pkg) {
        return """
                package %s.web;

                import %s.audit.OperationAuditLogRepository;
                import %s.identity.IdentityResolver;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                @ConditionalOnProperty(name = "keelbase.operations-audit.enabled", havingValue = "true",
                        matchIfMissing = true)
                public class OperationsAuditConfiguration implements WebMvcConfigurer {

                    private final OperationAuditInterceptor interceptor;

                    public OperationsAuditConfiguration(OperationAuditLogRepository logs,
                                                        IdentityResolver identities) {
                        this.interceptor = new OperationAuditInterceptor(logs, identities);
                    }

                    @Override
                    public void addInterceptors(InterceptorRegistry registry) {
                        registry.addInterceptor(interceptor);
                    }
                }
                """.formatted(pkg, pkg, pkg);
    }

    /**
     * The operations page's endpoint: the business writes, newest first, administrators only.
     *
     * 操作审计页的端点：业务写、最新在前、仅管理员。
     */
    private String operationAuditController(String pkg) {
        return """
                package %s.web;

                import %s.audit.OperationAuditLog;
                import %s.audit.OperationAuditLogRepository;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.data.domain.Page;
                import org.springframework.data.domain.PageRequest;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;

                /**
                 * The operations audit: what was done to the business, newest first — administrators only.
                 *
                 * <p>It reads a different chain from the decision trace: the rows here are business writes,
                 * including the ones no AI took part in. The object's row in the runtime's
                 * {@code docs/wire-object-endpoints.md} names what each chain carries and why several of
                 * the frozen object's fields are absent rather than empty.
                 *
                 * 操作审计：对**业务**做了什么，最新的在前 —— 仅管理员。
                 *
                 * <p>它读的链与决策轨迹**不同**：这里的行是**业务写**，包括**没有 AI 参与**的那些。
                 * 两条链各带什么、以及冻结对象的哪几个字段是**缺席**而不是空，见运行时
                 * `docs/wire-object-endpoints.md` 里该对象那一行。
                 */
                @RestController
                public class OperationAuditController {

                    private static final int MAX_LIMIT = 100;

                    private final OperationAuditLogRepository logs;
                    private final IdentityResolver identities;

                    public OperationAuditController(OperationAuditLogRepository logs, IdentityResolver identities) {
                        this.logs = logs;
                        this.identities = identities;
                    }

                    @GetMapping("/audit/operations/logs")
                    public Map<String, Object> logs(
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int limit,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal caller =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        if (!caller.isManager()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "the operations audit is readable by an administrator");
                        }
                        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
                        int index = Math.max(1, page) - 1;
                        Page<OperationAuditLog> found = logs.findAllByOrderByIdDesc(PageRequest.of(index, size));
                        List<Map<String, Object>> items = found.getContent().stream()
                                .map(OperationAuditController::view)
                                .toList();
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("total", found.getTotalElements());
                        body.put("page", index + 1);
                        body.put("limit", size);
                        body.put("items", items);
                        return body;
                    }

                    private static Map<String, Object> view(OperationAuditLog log) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", log.getId());
                        row.put("userId", log.getUserId());
                        row.put("action", log.getAction());
                        row.put("method", log.getMethod());
                        row.put("path", log.getPath());
                        row.put("targetId", log.getTargetId());
                        row.put("ip", log.getIp());
                        row.put("userAgent", log.getUserAgent());
                        row.put("statusCode", log.getStatusCode());
                        row.put("createdAt", log.getCreatedAt());
                        return row;
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg);
    }
}
