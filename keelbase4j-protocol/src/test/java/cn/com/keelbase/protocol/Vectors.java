// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DynamicTest;

/**
 * Loads the frozen language-neutral vectors.
 *
 * <p>The vector directory is the {@code keelbase.vectors.dir} system property (set by surefire to
 * the vendored {@code conformance/vectors}). That copy is a snapshot of the contract repository
 * ({@code rain6fish/keelbase-contract}), which is authoritative for it; {@code scripts/sync-vectors.sh}
 * refreshes it and CI gates it, so nothing here is hand-edited.
 */
public final class Vectors {

    private Vectors() {
    }

    public static Path dir() {
        String d = System.getProperty("keelbase.vectors.dir");
        if (d == null || d.isBlank()) {
            throw new IllegalStateException("keelbase.vectors.dir system property is not set");
        }
        return Path.of(d);
    }

    public static Object read(String fileName) {
        try {
            String text = Files.readString(dir().resolve(fileName), StandardCharsets.UTF_8);
            return Json.parse(text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read vector " + fileName + " from " + dir(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    public static String str(Map<String, Object> m, String key) {
        return (String) m.get(key);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        return (List<Object>) m.get(key);
    }

    /** Turn a JSON array of cases into JUnit dynamic tests, using each case's {@code id} as name. */
    public static List<DynamicTest> dynamic(List<Object> cases, Consumer<Object> check) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object c : cases) {
            String name = String.valueOf(map(c).get("id"));
            tests.add(DynamicTest.dynamicTest(name, () -> check.accept(c)));
        }
        return tests;
    }
}
