// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import cn.com.keelbase.gen.BusinessSpec.EntitySpec;
import cn.com.keelbase.gen.BusinessSpec.FieldSpec;
import cn.com.keelbase.gen.BusinessSpec.ToolSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Generate the project under {@code outDir}; return the files written. */
    public List<Path> generate(BusinessSpec spec, Path outDir) {
        String pkg = "com.example." + spec.module();
        Path javaDir = outDir.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Path resources = outDir.resolve("src/main/resources");
        List<Path> written = new ArrayList<>();

        written.add(write(outDir.resolve("pom.xml"), pom(spec)));
        written.add(write(outDir.resolve("README.md"), readme(spec)));
        written.add(write(resources.resolve("application.properties"), properties(spec.module())));
        written.add(write(javaDir.resolve("Application.java"), application(pkg)));

        for (EntitySpec entity : spec.entities()) {
            written.add(write(javaDir.resolve("domain/" + entity.name() + ".java"), entityClass(pkg, entity)));
            written.add(write(javaDir.resolve("domain/" + entity.name() + "Repository.java"), repository(pkg, entity)));
        }

        // Governance wiring (self-contained; the runtime is generated into the app).
        written.add(write(javaDir.resolve("ai/AiTool.java"), aiToolInterface(pkg)));
        written.add(write(javaDir.resolve("ai/GovernanceGate.java"), governanceGate(pkg)));
        written.add(write(javaDir.resolve("ai/ToolRegistry.java"), toolRegistry(pkg)));
        written.add(write(javaDir.resolve("ai/ConfirmationStore.java"), confirmationStore(pkg)));
        written.add(write(javaDir.resolve("ai/SideEffectStore.java"), sideEffectStore(pkg)));
        written.add(write(javaDir.resolve("ai/AuditChainStore.java"), auditChainStore(pkg)));
        written.add(write(javaDir.resolve("ai/GovernanceEngine.java"), governanceEngine(pkg)));

        for (ToolSpec tool : spec.tools()) {
            written.add(write(javaDir.resolve("ai/" + pascal(tool.name()) + "Tool.java"), toolClass(pkg, tool, spec)));
        }

        written.add(write(javaDir.resolve("web/AiController.java"), aiController(pkg)));
        written.add(write(javaDir.resolve("web/GovernanceController.java"), governanceController(pkg)));
        return written;
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
                    <version>3.2.5</version>
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
                      <artifactId>spring-boot-starter-web</artifactId>
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
        return sb.toString();
    }

    private String properties(String module) {
        return """
                spring.application.name=%s
                spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1
                spring.jpa.hibernate.ddl-auto=update
                """.formatted(module, module);
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
        sb.append("    public void setDeletedAt(Instant deletedAt) {\n        this.deletedAt = deletedAt;\n    }\n");
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

    // ── web ─────────────────────────────────────────────────────────────────────

    private String aiController(String pkg) {
        return """
                package %s.web;

                import %s.ai.GovernanceEngine;
                import %s.ai.ToolRegistry;
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

                    public AiController(ToolRegistry registry, GovernanceEngine engine) {
                        this.registry = registry;
                        this.engine = engine;
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
                            @RequestBody Map<String, Object> body) {
                        String tool = String.valueOf(body.getOrDefault("tool", ""));
                        Map<String, Object> args = new LinkedHashMap<>(body);
                        args.remove("tool");
                        return engine.execute(tool, args, userId == null ? "anonymous" : userId);
                    }

                    @PostMapping("/ai/confirmations/{token}")
                    public Map<String, Object> confirm(
                            @PathVariable String token,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestBody Map<String, Object> body) {
                        String decision = String.valueOf(body.getOrDefault("decision", ""));
                        if ("approve".equals(decision)) {
                            return engine.approve(token, userId == null ? "anonymous" : userId);
                        }
                        return Map.of("status", "declined");
                    }
                }
                """.formatted(pkg, pkg, pkg);
    }

    private String governanceController(String pkg) {
        return """
                package %s.web;

                import %s.ai.AuditChainStore;
                import %s.ai.SideEffectStore;
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

                    public GovernanceController(SideEffectStore sideEffects, AuditChainStore audit) {
                        this.sideEffects = sideEffects;
                        this.audit = audit;
                    }

                    @GetMapping("/ai/tool-effects")
                    public List<SideEffectStore.Effect> effects() {
                        return sideEffects.all();
                    }

                    @DeleteMapping("/ai/tool-effects/{id}")
                    public Map<String, Object> revoke(
                            @PathVariable Long id,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role) {
                        boolean manager = "manager".equals(role) || "admin".equals(role);
                        SideEffectStore.Effect effect =
                                sideEffects.requireOwned(id, userId == null ? "anonymous" : userId, manager);
                        // Local compensation: mark the effect revoked (the target stays for demo;
                        // a real app soft-deletes the referenced row here).
                        return Map.of("effectId", effect.id(), "revokeStatus", "revoked");
                    }

                    @GetMapping("/audit/verify")
                    public Map<String, Object> verify() {
                        return Map.of("valid", audit.verify(), "checked", audit.size());
                    }
                }
                """.formatted(pkg, pkg, pkg);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

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
