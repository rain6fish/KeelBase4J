// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser.
 *
 * <p>Produces the model used by {@link CanonicalJson}: {@code LinkedHashMap} for objects
 * (insertion order preserved), {@link List} for arrays, {@link String}, {@link Double} for all
 * numbers, {@link Boolean}, and {@code null}. Kept small on purpose — the protocol only needs
 * to read the frozen vector files; the interesting logic is the canonical <em>writer</em>.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    /** Parse a JSON document into the map/list/scalar model. */
    public static Object parse(String text) {
        Json p = new Json(stripBom(text));
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.src.length()) {
            throw p.err("trailing content");
        }
        return v;
    }

    private static String stripBom(String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                lit("true");
                return Boolean.TRUE;
            case 'f':
                lit("false");
                return Boolean.FALSE;
            case 'n':
                lit("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected , or }");
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        ws();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw err("expected , or ]");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    sb.append((char) Integer.parseInt(hex, 16));
                    break;
                default:
                    throw err("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw err("expected value");
        }
        return Double.valueOf(src.substring(start, pos));
    }

    private void lit(String s) {
        if (!src.startsWith(s, pos)) {
            throw err("expected " + s);
        }
        pos += s.length();
    }

    private void ws() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw err("unexpected end");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        char got = next();
        if (got != c) {
            throw err("expected " + c + " but got " + got);
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON parse error at offset " + pos + ": " + msg);
    }
}
