// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import cn.com.keelbase.gen.BusinessSpec.EntitySpec;
import cn.com.keelbase.gen.BusinessSpec.FieldSpec;
import cn.com.keelbase.gen.BusinessSpec.ToolSpec;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import java.io.IOException;
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
        if (applied.isEmpty()) {
            project.keep(migrationDir.resolve("V1__" + spec.module() + "_baseline.sql"),
                    migrationBaseline(spec));
        } else {
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

        for (ToolSpec tool : spec.tools()) {
            project.write(javaDir.resolve("ai/" + pascal(tool.name()) + "Tool.java"), toolClass(pkg, tool, spec));
        }

        // Identity seam + contract-derived authorization. The same shape the KeelBase4J runtime wires,
        // emitted as this app's own source: the identity is resolved once per request, and every
        // access decision comes out in the frozen permission-* vocabulary rather than from a role
        // string compared inline. The app still depends only on the protocol *library*.
        project.write(javaDir.resolve("identity/Principal.java"), principal(pkg));
        project.write(javaDir.resolve("identity/IdentityEvidence.java"), identityEvidence(pkg));
        project.write(javaDir.resolve("identity/IdentityResolver.java"), identityResolver(pkg));
        project.write(javaDir.resolve("identity/HeaderIdentityResolver.java"), headerIdentityResolver(pkg));
        project.write(javaDir.resolve("authz/AuthorizationRules.java"), authorizationRules(pkg, spec));
        project.write(javaDir.resolve("authz/PermissionAuthorizer.java"), permissionAuthorizer(pkg));
        project.write(javaDir.resolve("authz/OwnershipGuard.java"), ownershipGuard(pkg));

        project.write(javaDir.resolve("web/AiController.java"), aiController(pkg));
        project.write(javaDir.resolve("web/AuthController.java"), authController(pkg));
        project.write(javaDir.resolve("web/GovernanceController.java"), governanceController(pkg));
        // F4 + F5 of the frozen Full profile. Without them the artifact cannot serve the
        // runtime-neutral frontend at all: the frontend unwraps the envelope on every call, and it
        // reads the capability surface before it holds a token.
        project.write(javaDir.resolve("web/WireEnvelope.java"), wireEnvelope(pkg));
        project.write(javaDir.resolve("web/ApiResponseAdvice.java"), apiResponseAdvice(pkg));
        project.write(javaDir.resolve("web/WireErrorController.java"), wireErrorController(pkg));
        project.write(javaDir.resolve("web/WireExceptionHandler.java"), wireExceptionHandler(pkg));
        project.write(javaDir.resolve("web/AppInfoController.java"), appInfoController(pkg, spec));
        // One CRUD controller for the primary entity, enforcing the spec's policy rules.
        EntitySpec primary = spec.entities().get(0);
        project.write(javaDir.resolve("web/" + primary.name() + "Controller.java"),
                entityController(pkg, primary, spec));
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
                      <version>0.1.0-SNAPSHOT</version>
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
                """.formatted(spec.module());
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
        sb.append("## AI tools\n\n| tool | risk | confirmation |\n|---|---|---|\n");
        for (ToolSpec t : spec.tools()) {
            sb.append("| `").append(t.name()).append("` | ").append(t.riskLevel())
                    .append(" | ").append(t.requiresConfirmation() ? "yes" : "no").append(" |\n");
        }
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
                import org.springframework.stereotype.Component;

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

                    public Pending requireOwned(String token, String userId) {
                        Pending p = pending.get(token);
                        if (p == null) {
                            throw new IllegalArgumentException("unknown confirmation token");
                        }
                        if (!p.userId().equals(userId)) {
                            throw new IllegalStateException("confirmation belongs to another operator");
                        }
                        return p;
                    }

                    @SuppressWarnings("unchecked")
                    public Map<String, Object> args(Pending p) {
                        return new LinkedHashMap<>((Map<String, Object>) Json.parse(p.argsJson()));
                    }

                    public void remove(String token) {
                        pending.remove(token);
                    }
                }
                """.formatted(pkg);
    }

    private String sideEffectStore(String pkg) {
        return """
                package %s.ai;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.concurrent.atomic.AtomicLong;
                import org.springframework.stereotype.Component;

                /** Records AI write side effects so they can be revoked (local compensation). */
                @Component
                public class SideEffectStore {

                    public record Effect(Long id, String userId, String toolName, String resultType,
                                         Long resultId, String revokeStatus) {
                    }

                    private final AtomicLong seq = new AtomicLong();
                    private final List<Effect> effects = new ArrayList<>();

                    public synchronized Effect record(String userId, String toolName, String resultType, Long resultId) {
                        Effect e = new Effect(seq.incrementAndGet(), userId, toolName, resultType, resultId, "executed");
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
                                        e.resultId(), "revoked"));
                            }
                        }
                    }

                    public synchronized List<Effect> all() {
                        return List.copyOf(effects);
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

                    public GovernanceEngine(ToolRegistry registry, GovernanceGate gate,
                                            ConfirmationStore confirmations, SideEffectStore sideEffects,
                                            AuditChainStore audit) {
                        this.registry = registry;
                        this.gate = gate;
                        this.confirmations = confirmations;
                        this.sideEffects = sideEffects;
                        this.audit = audit;
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

                    public Map<String, Object> approve(String token, String userId) {
                        ConfirmationStore.Pending pending = confirmations.requireOwned(token, userId);
                        AiTool tool = registry.require(pending.toolName());
                        audit.append("tool_confirmation", userId, tool.name() + " approved");
                        Map<String, Object> out = run(tool, confirmations.args(pending), userId);
                        confirmations.remove(token);
                        return out;
                    }

                    private Map<String, Object> run(AiTool tool, Map<String, Object> args, String userId) {
                        Map<String, Object> result = tool.execute(args, userId);
                        Long effectId = null;
                        if (Boolean.TRUE.equals(result.get("success")) && result.get("resultId") instanceof Number n) {
                            Long resultId = n.longValue();
                            effectId = sideEffects.record(userId, tool.name(), tool.name(), resultId).id();
                        }
                        audit.append("tool_call", userId, tool.name() + " -> ok");
                        Map<String, Object> out = status("executed");
                        out.put("result", result);
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

                    @Override
                    public Map<String, Object> execute(Map<String, Object> args, String userId) {
                %s    }
                }
                """
                .formatted(pkg, pkg, detail.name(), pkg, detail.name(), tool.description(), pascal(tool.name()),
                        detail.name(), decap(detail.name()), pascal(tool.name()), detail.name(), decap(detail.name()),
                        decap(detail.name()), decap(detail.name()), tool.name(), tool.riskLevel(), body);
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

                    public IdentityEvidence {
                        attributes = Map.copyOf(attributes);
                    }

                    public static IdentityEvidence ofHeaders(String userId, String role, String oidcSubject) {
                        Map<String, String> attributes = new LinkedHashMap<>();
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
                 * The default adapter: reads the caller from request headers.
                 *
                 * <p>{@code X-User-Id} (required), {@code X-User-Role} (default {@code user}) and an
                 * optional {@code X-Oidc-Sub} standing in for an SSO subject. The rule it upholds is the
                 * one the runtime upholds: every governed operation carries an identity, and nothing runs
                 * anonymously — a request with no identity is rejected rather than defaulted.
                 */
                @Component
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
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class AiController {

                    private final ToolRegistry registry;
                    private final GovernanceEngine engine;
                    private final IdentityResolver identities;

                    public AiController(ToolRegistry registry, GovernanceEngine engine,
                                        IdentityResolver identities) {
                        this.registry = registry;
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
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject,
                            @RequestBody Map<String, Object> body) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
                        String tool = String.valueOf(body.getOrDefault("tool", ""));
                        Map<String, Object> args = new LinkedHashMap<>(body);
                        args.remove("tool");
                        return engine.execute(tool, args, principal.userId());
                    }

                    @PostMapping("/ai/confirmations/{token}")
                    public Map<String, Object> confirm(
                            @PathVariable String token,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject,
                            @RequestBody Map<String, Object> body) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
                        String decision = String.valueOf(body.getOrDefault("decision", ""));
                        if ("approve".equals(decision)) {
                            return engine.approve(token, principal.userId());
                        }
                        return Map.of("status", "declined");
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
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
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
     * <p>Served under {@code /api/v1} to match the prefix the frontend's API base defaults to.
     *
     * <p>The module label is baked in at generation time: the business request this artifact came from
     * carries no human-facing label for the module, so the entity name stands in and the description is
     * empty. The contract asks for strings, not for particular content.
     */
    private String appInfoController(String pkg, BusinessSpec spec) {
        String moduleId = spec.module();
        String moduleLabel = spec.entities().get(0).name();
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
                @RequestMapping("/api/v1/app")
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

    private String governanceController(String pkg) {
        return """
                package %s.web;

                import %s.ai.AuditChainStore;
                import %s.ai.SideEffectStore;
                import %s.identity.IdentityEvidence;
                import %s.identity.IdentityResolver;
                import %s.identity.Principal;
                import java.util.List;
                import java.util.Map;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class GovernanceController {

                    private final SideEffectStore sideEffects;
                    private final AuditChainStore audit;
                    private final IdentityResolver identities;

                    public GovernanceController(SideEffectStore sideEffects, AuditChainStore audit,
                                                IdentityResolver identities) {
                        this.sideEffects = sideEffects;
                        this.audit = audit;
                        this.identities = identities;
                    }

                    @GetMapping("/ai/tool-effects")
                    public List<SideEffectStore.Effect> effects(
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
                        if (principal.isManager()) {
                            return sideEffects.all();
                        }
                        return sideEffects.all().stream()
                                .filter(effect -> effect.userId().equals(principal.userId()))
                                .toList();
                    }

                    @DeleteMapping("/ai/tool-effects/{id}")
                    public Map<String, Object> revoke(
                            @PathVariable Long id,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
                        Principal principal =
                                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
                        // The row-level "is this yours?" check stays in the store, as it does in the
                        // runtime; what changed is that the role it consults is the contract's, not a
                        // string compared here.
                        SideEffectStore.Effect effect = sideEffects.requireOwned(
                                id, principal.userId(), principal.isManager());
                        // Local compensation: mark the effect revoked (the target stays for demo;
                        // a real app soft-deletes the referenced row here).
                        return Map.of("effectId", effect.id(), "revokeStatus", "revoked");
                    }

                    @GetMapping("/audit/verify")
                    public Map<String, Object> verify() {
                        return Map.of("valid", audit.verify(), "checked", audit.size());
                    }
                }
                """.formatted(pkg, pkg, pkg, pkg, pkg, pkg);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * A CRUD controller for the primary entity. Which actions a caller may take, and whether the rows
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
        sb.append("                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));\n");
        sb.append("        ownership.requireAction(principal, \"").append(name).append("\", \"create\");\n");
        sb.append("        body.setOwnerUserId(principal.userId());\n");
        sb.append("        return repository.save(body);\n    }\n\n");

        sb.append("    @GetMapping(\"/").append(plural).append("\")\n");
        sb.append("    public List<").append(name).append("> list(\n");
        sb.append(identityHeaders());
        sb.append("        Principal principal =\n");
        sb.append("                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));\n");
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
        sb.append("                identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));\n");
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

    /** The three identity headers every governed endpoint reads before resolving a principal. */
    private static String identityHeaders() {
        return "            @RequestHeader(value = \"X-User-Id\", required = false) String userId,\n"
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
}
