// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.runtime.effect.WriteClaimReleaseService;
import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * One side of a cross-process release race: boot the real runtime, wait for a shared wall-clock
 * instant, then release each claim in turn and say what came back.
 *
 * <p>It is the whole application, not a test slice — a second instance of the deployment is what the
 * race has to cross, so the process boundary must be real. The coordinator starts two of these with
 * the same claim ids, the same start instant and different notes; each prints one {@code RESULT} line
 * per round, which the test parses. Nothing here decides anything: the arbitration is the database's
 * conditional update, and this class only carries a caller to it.
 *
 * <p>Arguments: {@code rounds idsCsv notesCsv startAtEpochMillis spacingMillis}. The database comes
 * from the same {@code KEELBASE_PG_URL} / {@code KEELBASE_PG_USER} / {@code KEELBASE_PG_PASSWORD}
 * convention the rest of the repository uses; without it the harness refuses to start.
 *
 * 跨进程释放竞争的一侧：引导**真实的运行时**，等一个共享的 wall-clock 时刻，然后逐个释放占位并说出结果。
 *
 * <p>它起的是**整个应用**、不是测试切片 —— 竞争要跨过的正是**部署的第二个实例**，故进程边界必须是真的。
 * 协调者用相同的占位 id、相同的起跑时刻、不同的说明启动两个本类；每轮各打印一行 `RESULT`，由测试解析。
 * 这里不做任何裁决：仲裁是数据库的条件更新，本类只把一个调用方带到它面前。
 *
 * <p>参数：`rounds idsCsv notesCsv startAtEpochMillis spacingMillis`。数据库沿用本仓的
 * `KEELBASE_PG_URL` / `KEELBASE_PG_USER` / `KEELBASE_PG_PASSWORD` 约定；没有它则拒绝启动。
 */
public final class CrossProcessReleaseHarness {

    private CrossProcessReleaseHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "usage: <rounds> <idsCsv> <notesCsv> <startAtEpochMillis> <spacingMillis>");
        }
        int rounds = Integer.parseInt(args[0]);
        String[] ids = args[1].split(",");
        String[] notes = args[2].split(",");
        long startAt = Long.parseLong(args[3]);
        long spacing = Long.parseLong(args[4]);
        if (ids.length < rounds || notes.length < rounds) {
            throw new IllegalArgumentException("need one id and one note per round");
        }

        try (ConfigurableApplicationContext context = start()) {
            WriteClaimReleaseService releases = context.getBean(WriteClaimReleaseService.class);
            Principal carol = new Principal("carol", Principal.ROLE_ADMIN);
            System.out.println("READY pid=" + ProcessHandle.current().pid()
                    + " at=" + System.currentTimeMillis() + " startAt=" + startAt
                    + " url=" + context.getEnvironment().getProperty("spring.datasource.url"));
            for (int round = 0; round < rounds; round++) {
                awaitUntil(startAt + round * spacing);
                try {
                    releases.release(Long.parseLong(ids[round]), carol, notes[round], null);
                    System.out.println("RESULT round=" + round + " won=true status=200");
                } catch (ResponseStatusException refused) {
                    System.out.println("RESULT round=" + round + " won=false status="
                            + refused.getStatusCode().value() + " reason=" + refused.getReason());
                }
            }
        }
    }

    private static ConfigurableApplicationContext start() {
        String url = System.getenv("KEELBASE_PG_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "KEELBASE_PG_URL must point at the PostgreSQL both processes share");
        }
        // Command-line arguments, not default properties: default properties sit below
        // application.properties, so the shipped H2 URL would win and each child would race on its
        // own in-memory database — measured, and it looks exactly like "unknown write claim".
        //
        // 用命令行参数、不用 default properties：后者在 `application.properties` 之下，自带的 H2 URL
        // 会压过它，于是每个子进程都在自己的内存库上跑 —— 已实测，而它看起来正好是「unknown write claim」。
        return new SpringApplicationBuilder(KeelBase4JApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.datasource.username=" + envOrDefault("KEELBASE_PG_USER", "keelbase"),
                        "--spring.datasource.password="
                                + envOrDefault("KEELBASE_PG_PASSWORD", "keelbase"));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void awaitUntil(long epochMillis) throws InterruptedException {
        long remaining;
        while ((remaining = epochMillis - System.currentTimeMillis()) > 0) {
            if (remaining > 20) {
                Thread.sleep(remaining - 20);
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
