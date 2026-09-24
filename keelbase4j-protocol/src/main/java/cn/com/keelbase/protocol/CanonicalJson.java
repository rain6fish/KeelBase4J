// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical JSON, byte-faithful to the reference implementation's
 * {@code JSON.stringify(payload, sortedKeys)} where {@code sortedKeys} is the array of top-level
 * keys (undefined dropped) sorted lexicographically.
 *
 * <p>Behaviour this deliberately reproduces (see the vendored {@code canonical-json-v1-vector.json}):
 * <ul>
 *   <li>the key list is the <em>top-level</em> object's keys, sorted with UTF-16 code-unit order
 *       (Java's {@link String#compareTo} is the same ordering);</li>
 *   <li>the key list acts as a <b>replacer array</b> applied at <em>every</em> level: any nested
 *       object emits only those of its keys that appear in the top-level key set — a nested key
 *       absent from the set is dropped, so {@code {a:{x:1,y:2},b:5}} serialises to {@code {"a":{},"b":5}},
 *       and a nested key that happens to share a name with a top-level key is kept;</li>
 *   <li>array elements are emitted in order, with the same object filtering applied to any object
 *       element; scalars pass through;</li>
 *   <li>numbers use ECMAScript {@code Number::toString} style ({@code -0 → "0"}, {@code 1e21 → "1e+21"});</li>
 *   <li>strings escape only the mandatory set; non-ASCII is emitted literally (no {@code \\u} escaping).</li>
 * </ul>
 *
 * <p>This is the load-bearing piece of cross-runtime compatibility: the audit-chain hash is taken
 * over these exact bytes.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** Canonicalise a parsed JSON value (see {@link Json#parse}). */
    public static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> keys = sortedKeys(map);
            StringBuilder sb = new StringBuilder();
            writeObject(sb, map, keys);
            return sb.toString();
        }
        // Top-level non-object: no replacer key list applies; serialise as-is.
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, null);
        return sb.toString();
    }

    /**
     * Plain {@code JSON.stringify(value)} — all keys, insertion order, no replacer filtering.
     * Used where the reference builds a payload by hand (e.g. JWT claims) and the exact byte
     * layout matters for cross-runtime signature equality.
     */
    public static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, null);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> sortedKeys(Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) {
            keys.add((String) k);
        }
        keys.sort(null); // String.compareTo == JS default sort (UTF-16 code units)
        return keys;
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, List<String> keys) {
        sb.append('{');
        // keys == null  → JS JSON.stringify default: every key, insertion order.
        // keys != null  → replacer-array semantics: only those keys, in the given (sorted) order.
        Iterable<String> order = (keys != null) ? keys : stringKeys(map);
        boolean first = true;
        for (String k : order) {
            if (!map.containsKey(k)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, k);
            sb.append(':');
            writeValue(sb, map.get(k), keys);
        }
        sb.append('}');
    }

    private static List<String> stringKeys(Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) {
            keys.add((String) k);
        }
        return keys;
    }

    private static void writeValue(StringBuilder sb, Object v, List<String> keys) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> map) {
            writeObject(sb, map, keys);
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, list.get(i), keys);
            }
            sb.append(']');
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Number n) {
            sb.append(number(n.doubleValue()));
        } else {
            throw new IllegalArgumentException("unsupported value type: " + v.getClass());
        }
    }

    /** JS string quoting: escape only what JSON/JS requires; emit non-ASCII literally. */
    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * ECMAScript-approximate {@code Number → String}. Covers the vector's edge cases
     * ({@code -0}, {@code 1e21}, decimals, integers, negatives).
     */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null"; // JSON.stringify(NaN|Infinity) === "null"
        }
        if (d == 0.0) {
            return "0"; // also normalises -0.0
        }
        double abs = Math.abs(d);
        if (abs >= 1e-6 && abs < 1e21) {
            if (d == Math.rint(d) && abs < 1e15) {
                return Long.toString((long) d);
            }
            BigDecimal bd = BigDecimal.valueOf(d).stripTrailingZeros();
            return bd.toPlainString();
        }
        // Exponential form: mantissa e±exponent
        BigDecimal bd = BigDecimal.valueOf(d);
        int exp = bd.precision() - bd.scale() - 1;
        BigDecimal mant = bd.movePointLeft(exp).stripTrailingZeros();
        return mant.toPlainString() + "e" + (exp < 0 ? "-" : "+") + Math.abs(exp);
    }
}
