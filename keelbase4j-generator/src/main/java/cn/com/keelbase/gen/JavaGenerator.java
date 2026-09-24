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
        // F4 + F5 of the frozen Full profile. Without them the artifact cannot serve the
        // runtime-neutral frontend at all: the frontend unwraps the envelope on every call, and it
        // reads the capability surface before it holds a token.
        project.write(javaDir.resolve("web/WireEnvelope.java"), wireEnvelope(pkg));
        project.write(javaDir.resolve("web/ApiResponseAdvice.java"), apiResponseAdvice(pkg));
        project.write(javaDir.resolve("web/WireErrorController.java"), wireErrorController(pkg));
        project.write(javaDir.resolve("web/WireExceptionHandler.java"), wireExceptionHandler(pkg));
        project.write(javaDir.resolve("web/AppInfoController.java"), appInfoController(pkg, spec));
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
        sb.append("->  { conversationId, reply, provider, model, toolCalls?, status, data, token, effectId, error }\n");
        sb.append("```\n\n");
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
        sb.append("DELETE /ai/tool-effects/{id}   ->  { effectId, resultType, revokeClass, revokeStatus }\n```\n\n");
        sb.append("The envelope and the item's fields are the shape the runtime-neutral console reads — the\n");
        sb.append("same one the KeelBase4J runtime answers, so the console pages and renders both. `limit` is\n");
        sb.append("capped at 100; a non-manager sees their own effects whatever id they pass, and a manager\n");
        sb.append("may narrow the list by `userId`.\n\n");
        sb.append("`targetExists` / `targetSoftDeleted` / `targetTitle` describe the row the write created,\n");
        sb.append("looked up at read time (its note is the title the console shows). `revokeClass` is\n");
        sb.append("`local_compensate` because every write here is a local row and revoking one really does\n");
        sb.append("compensate: the target is soft-deleted. `conversationId` is **null** — an approval arrives\n");
        sb.append("in a request of its own, so the record does not carry the conversation that proposed the\n");
        sb.append("write; the runtime answers null here too rather than inventing a link.\n\n");
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
        sb.append("`application.properties`). Nothing runs anonymously: a request without a valid token is a 401.\n\n");
        sb.append("`LocalIdentities` says which local user and role a *verified* subject holds. It ships with the\n");
        sb.append("demo entries; **replace them with your directory**. A role is never taken from the request —\n");
        sb.append("a caller that could state its own role would grant itself one.\n\n");
        sb.append("`HeaderIdentityResolver` ships for deployments that sit behind a guard which has already\n");
        sb.append("authenticated the caller; declare it as your `IdentityResolver` bean in that shape, or\n");
        sb.append("implement the `IdentityResolver` interface for anything else.\n");
        sb.append("\n## Run\n\n```\nmvn spring-boot:run\n```\n");
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
        columns.add(new Column("deleted_at", "TIMESTAMP"));
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

                public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

                    List<ConversationMessage> findByConversationIdOrderByIdAsc(String conversationId);
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
                    created_at TIMESTAMP NOT NULL
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

                    public record Pending(String token, String toolName, String argsJson, String userId, String riskLevel) {
                    }

                    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

                    public String create(String userId, String toolName, Map<String, Object> args, String riskLevel) {
                        String token = UUID.randomUUID().toString();
                        pending.put(token, new Pending(token, toolName, CanonicalJson.json(args), userId, riskLevel));
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
                 */
                @Component
                public class SideEffectStore {

                    public record Effect(Long id, String userId, String toolName, String resultType,
                                         Long resultId, String revokeClass, String revokeStatus,
                                         String argsHash, Instant createdAt) {
                    }

                    private final AtomicLong seq = new AtomicLong();
                    private final List<Effect> effects = new ArrayList<>();

                    public synchronized Effect record(String userId, String toolName, String resultType,
                                                      Long resultId, Map<String, Object> args) {
                        // Every write this application performs is on its own row, so the honest class is
                        // local compensation — and the revoke path does perform it: the controller
                        // soft-deletes the target. Never `governed_external` here: nothing outside this
                        // application is being compensated, and claiming a compensation channel that does
                        // not exist is how a console ends up offering a button that cannot work.
                        Effect e = new Effect(seq.incrementAndGet(), userId, toolName, resultType, resultId,
                                "local_compensate", "executed", argsHash(args), Instant.now());
                        effects.add(e);
                        return e;
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
                                        e.resultId(), e.revokeClass(), "revoked", e.argsHash(), e.createdAt()));
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
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                /** A hash-chained AI audit log (in-process; single-instance). */
                @Component
                public class AuditChainStore {

                    public record Row(int id, String prevHash, String hash) {
                    }

                    private final List<Map<String, Object>> payloads = new ArrayList<>();
                    private final List<Row> rows = new ArrayList<>();
                    private final String key;

                    public AuditChainStore(@Value("${keelbase.audit.hmac-key:abababababababababababababababababababababababababababababababab}")
                                           String key) {
                        this.key = key;
                    }

                    public synchronized void append(String action, String userId, String detail) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("action", action);
                        payload.put("userId", userId);
                        payload.put("detail", detail);
                        String prev = rows.isEmpty() ? null : rows.get(rows.size() - 1).hash();
                        String hash = AuditChain.hash(key, prev, payload);
                        payloads.add(payload);
                        rows.add(new Row(rows.size() + 1, prev, hash));
                    }

                    public synchronized boolean verify() {
                        for (int i = 0; i < rows.size(); i++) {
                            Row row = rows.get(i);
                            String prev = i == 0 ? null : rows.get(i - 1).hash();
                            if (!java.util.Objects.equals(prev, row.prevHash())) {
                                return false;
                            }
                            if (!AuditChain.hash(key, prev, payloads.get(i)).equals(row.hash())) {
                                return false;
                            }
                        }
                        return true;
                    }

                    public synchronized int size() {
                        return rows.size();
                    }
                }
                """.formatted(pkg);
    }

    private String governanceEngine(String pkg) {
        return """
                package %s.ai;

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

                    public Map<String, Object> execute(String toolName, Map<String, Object> args, String userId) {
                        AiTool tool = registry.require(toolName);
                        String decision = gate.decide(tool);
                        if ("BLOCK".equals(decision)) {
                            audit.append("tool_call", userId, toolName + " blocked");
                            return status("blocked");
                        }
                        if ("ALLOW".equals(decision)) {
                            return run(tool, args, userId);
                        }
                        String token = confirmations.create(userId, toolName, args, tool.riskLevel());
                        audit.append("tool_call", userId, toolName + " pending confirmation");
                        Map<String, Object> out = status("pending_confirmation");
                        out.put("token", token);
                        return out;
                    }

                    /**
                     * Approve and perform the write. <b>The claim comes first</b>: only the caller that
                     * takes the token out of pending reaches the tool, so a second approval arriving at
                     * the same moment is refused rather than executing the write a second time.
                     */
                    public Map<String, Object> approve(String token, String userId) {
                        ConfirmationStore.Pending pending = confirmations.claim(token, userId);
                        AiTool tool = registry.require(pending.toolName());
                        audit.append("tool_confirmation", userId, tool.name() + " approved");
                        Map<String, Object> outcome = run(tool, confirmations.args(pending), userId);
                        // Last, and after the write is done: whoever is watching the stream hears about the
                        // decision once it is a fact. Nothing is waiting when nobody opened a stream, and
                        // that is an ordinary outcome rather than a failure.
                        watchers.decided(token, decision("approve", pending.toolName(), outcome));
                        return outcome;
                    }

                    /** Decline — the token is consumed and nothing is written. */
                    public Map<String, Object> decline(String token, String userId) {
                        ConfirmationStore.Pending pending = confirmations.claim(token, userId);
                        audit.append("tool_confirmation", userId, pending.toolName() + " declined");
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

                    private Map<String, Object> run(AiTool tool, Map<String, Object> args, String userId) {
                        Map<String, Object> result = tool.execute(args, userId);
                        Long effectId = null;
                        if (Boolean.TRUE.equals(result.get("success")) && result.get("resultId") instanceof Number n) {
                            Long resultId = n.longValue();
                            // The tool's *declared* result type, not its name: the console groups effects
                            // by what they produced. The arguments go in with it — the effect's argsHash
                            // is what the console shows, and a record that dropped them could not carry one.
                            effectId = sideEffects.record(userId, tool.name(), tool.resultType(), resultId, args).id();
                        }
                        audit.append("tool_call", userId, tool.name() + " -> ok");
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
                        decap(detail.name()), decap(detail.name()), tool.name(), tool.riskLevel(),
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
                     */
                    private String describe(Map<String, Object> outcome) {
                        if (outcome == null) {
                            return "I could not match that to a tool I can call, so I proposed nothing.";
                        }
                        Object status = outcome.get("status");
                        return switch (status == null ? "" : status.toString()) {
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
                import java.util.List;
                import java.util.Map;
                import org.springframework.stereotype.Component;

                /**
                 * The authorization rule source — who may do what, before any decision is taken.
                 *
                 * <p>Generated from the business specification. The decisions themselves live in
                 * {@link PermissionAuthorizer}, which reads rules only through {@link #rulesFor(String)},
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
                }
                """.formatted(pkg, userRules);
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
                        for (AuthorizationRules.Rule rule : rules.rulesFor(principal.role())) {
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
                        for (AuthorizationRules.Rule rule : rules.rulesFor(principal.role())) {
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
                     * The customer the console is looking at, as it writes it into the message: 「Acme」（ID 1）.
                     *
                     * <p>One reader for this, here rather than in the planner: the reference is conversation
                     * state, and this is where the transcript is. A planner receives the resolved id in its
                     * context and routes on it.
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
                                : engine.execute(plan.tool(), plan.args(), principal.userId());

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
                     * <p>The console names it once — in the first message ("当前客户「Acme」（ID 1）。给客户
                     * 建一条跟进记录"), because no client sends a customer id as a field. A model reads that
                     * reference wherever it appears; an application without one has to as well, or the second
                     * write in a conversation — "再建一条" — reaches a tool with nobody to act on. So the most
                     * recent mention wins, and the caller's explicit id beats everything.
                     *
                     * <p>Reading the transcript is what the store is for: this is not embeddings, not
                     * retrieval and not a memory policy (ADR-0013 D4 — a transcript, not memory).
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
                        // R3 — the operator confirms their own write. This application has no R4 approval
                        // mode, and saying it did would put a claim on the wire that nothing backs.
                        confirmation.put("mode", "confirmation");
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
                import %s.ai.ToolRegistry;
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
                 * {@code provider}, {@code model}, an optional {@code toolCalls}, and the governance facts
                 * {@code status}, {@code data}, {@code token}, {@code effectId}, {@code error}.
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
                    private final IdentityResolver identities;

                    public AiController(ToolRegistry registry, ChatTurnService turns, GovernanceEngine engine,
                                        IdentityResolver identities) {
                        this.registry = registry;
                        this.turns = turns;
                        this.engine = engine;
                        this.identities = identities;
                    }

                    @GetMapping("/ai/tools")
                    public List<Map<String, Object>> tools() {
                        return registry.all().stream().map(tool -> {
                            Map<String, Object> view = new LinkedHashMap<>();
                            view.put("name", tool.name());
                            view.put("riskLevel", tool.riskLevel());
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

                        Map<String, Object> outcome = turn.outcome();
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
                        answer.put("status", outcome == null ? null : outcome.get("status"));
                        answer.put("data", outcome == null ? null : outcome.get("data"));
                        answer.put("token", outcome == null ? null : outcome.get("token"));
                        answer.put("effectId", outcome == null ? null : outcome.get("effectId"));
                        answer.put("error", outcome == null ? null : outcome.get("error"));
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
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg);
    }

    private String authController(String pkg) {
        return """
                package %s.web;

                import %s.authz.PermissionAuthorizer;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import java.util.Map;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;

                /**
                 * The identity surface: what the caller may do.
                 *
                 * <p>{@code GET /auth/me/permissions} answers in the frozen
                 * {@code permission-capability-list} contract — the same path and the same shape the
                 * runtime and the reference implementation serve, which is what lets one frontend key
                 * page/menu/button visibility off either backend.
                 */
                @RestController
                public class AuthController {

                    private final IdentityResolver identities;
                    private final PermissionAuthorizer authorizer;

                    public AuthController(IdentityResolver identities, PermissionAuthorizer authorizer) {
                        this.identities = identities;
                        this.authorizer = authorizer;
                    }

                    @GetMapping("/auth/me/permissions")
                    public Map<String, Object> myPermissions(
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(authorization, userId, role, oidcSubject));
                        return authorizer.describe(principal).toWire();
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg);
    }

    // ── the frozen wire surface: envelope (F4) and frontend contract (F5) ───────

    /**
     * Builds the two frozen REST shapes — {@code api-response} for success, {@code error-body} for
     * failure (main repo {@code specs/protocol/schemas/v1}).
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
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

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
                        // The console reads the outcome of a revoke off the effect, as the runtime's
                        // answer does — not off a boolean of this application's own invention.
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("effectId", effect.id());
                        out.put("resultType", effect.resultType());
                        out.put("revokeClass", effect.revokeClass());
                        out.put("revokeStatus", "revoked");
                        return out;
                    }

                    /** One effect, as the console's model requires it: every field present. */
                    private Map<String, Object> view(SideEffectStore.Effect effect) {
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("id", effect.id());
                        view.put("toolName", effect.toolName());
                        // Null rather than invented: an approval arrives in a request of its own, and the
                        // effect record does not carry the conversation that proposed the write. The
                        // runtime's own surface answers null here for the same reason.
                        view.put("conversationId", null);
                        view.put("resultType", effect.resultType());
                        view.put("resultId", effect.resultId());
                        view.put("argsHash", effect.argsHash());
                        view.put("createdAt", effect.createdAt().toString());
                        Optional<%s> target = target(effect);
                        view.put("targetExists", target.isPresent());
                        view.put("targetSoftDeleted", target.map(row -> row.getDeletedAt() != null).orElse(false));
                %s
                        view.put("revokeClass", effect.revokeClass());
                        view.put("revokeStatus", effect.revokeStatus());
                        view.put("status", effect.revokeStatus());
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

                    @GetMapping("/audit/verify")
                    public Map<String, Object> verify() {
                        return Map.of("valid", audit.verify(), "checked", audit.size());
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
}
