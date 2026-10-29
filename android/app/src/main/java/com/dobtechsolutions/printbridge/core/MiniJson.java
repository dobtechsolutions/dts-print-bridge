package com.dobtechsolutions.printbridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser/quoter (objects, arrays, strings, numbers, booleans, null). No dependencies. */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("empty body");
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing data");
        return v;
    }

    static String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected key");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("bad unicode escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Number number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("unexpected character");
        String n = s.substring(start, i);
        try {
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.parseLong(n);
            return Double.parseDouble(n);
        } catch (NumberFormatException ex) {
            throw err("bad number");
        }
    }
}
