package com.avyansh.battleroyale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer (pure Java, so the networking code can be unit-tested off-device).
 * Objects are {@code Map<String,Object>}, arrays {@code List<Object>}, numbers {@code Double}.
 */
public final class Json {
	private Json() {}

	public static Object parse(String s) {
		P p = new P(s);
		p.ws();
		Object v = p.value();
		p.ws();
		if (p.i != s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String s) {
		Object o = parse(s);
		if (!(o instanceof Map)) throw new IllegalArgumentException("not an object");
		return (Map<String, Object>) o;
	}

	public static String write(Object o) {
		StringBuilder sb = new StringBuilder(128);
		w(sb, o);
		return sb.toString();
	}

	/** Builder: Json.obj("t","input","x",1.5) */
	public static Map<String, Object> obj(Object... kv) {
		LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	// ---- typed getters (never throw; defaults on mismatch)
	public static String str(Map<String, Object> m, String k, String d) { Object v = m.get(k); return v instanceof String ? (String) v : d; }
	public static double num(Map<String, Object> m, String k, double d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).doubleValue() : d; }
	public static float f(Map<String, Object> m, String k, float d) { return (float) num(m, k, d); }
	public static int i(Map<String, Object> m, String k, int d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
	public static boolean bool(Map<String, Object> m, String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }
	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Map ? (Map<String, Object>) v : null; }
	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof List ? (List<Object>) v : null; }
	public static double dbl(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0.0; }

	private static void w(StringBuilder sb, Object o) {
		if (o == null) { sb.append("null"); }
		else if (o instanceof String) { q(sb, (String) o); }
		else if (o instanceof Boolean) { sb.append(((Boolean) o) ? "true" : "false"); }
		else if (o instanceof Float || o instanceof Double) {
			double d = ((Number) o).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("0");
			else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
			else sb.append(d);
		}
		else if (o instanceof Number) { sb.append(((Number) o).longValue()); }
		else if (o instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Object e : ((Map<?, ?>) o).entrySet()) {
				Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
				if (!first) sb.append(',');
				first = false;
				q(sb, String.valueOf(en.getKey()));
				sb.append(':');
				w(sb, en.getValue());
			}
			sb.append('}');
		}
		else if (o instanceof Iterable) {
			sb.append('[');
			boolean first = true;
			for (Object e : (Iterable<?>) o) { if (!first) sb.append(','); first = false; w(sb, e); }
			sb.append(']');
		}
		else if (o instanceof int[]) { int[] a = (int[]) o; sb.append('['); for (int k = 0; k < a.length; k++) { if (k > 0) sb.append(','); sb.append(a[k]); } sb.append(']'); }
		else q(sb, String.valueOf(o));
	}

	private static void q(StringBuilder sb, String s) {
		sb.append('"');
		for (int k = 0; k < s.length(); k++) {
			char c = s.charAt(k);
			switch (c) {
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
			}
		}
		sb.append('"');
	}

	private static final class P {
		final String s; int i;
		P(String s) { this.s = s; }
		void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }
		RuntimeException err(String m) { return new IllegalArgumentException(m + " at " + i); }
		Object value() {
			if (i >= s.length()) throw err("eof");
			char c = s.charAt(i);
			if (c == '{') return object();
			if (c == '[') return array();
			if (c == '"') return string();
			if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
			if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
			if (s.startsWith("null", i)) { i += 4; return null; }
			return number();
		}
		Object object() {
			LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
			i++; ws();
			if (s.charAt(i) == '}') { i++; return m; }
			while (true) {
				ws();
				if (i >= s.length() || s.charAt(i) != '"') throw err("key expected");
				String k = string(); ws();
				if (s.charAt(i++) != ':') throw err(": expected");
				ws(); m.put(k, value()); ws();
				char c = s.charAt(i++);
				if (c == '}') return m;
				if (c != ',') throw err(", expected");
			}
		}
		Object array() {
			ArrayList<Object> a = new ArrayList<Object>();
			i++; ws();
			if (s.charAt(i) == ']') { i++; return a; }
			while (true) {
				ws(); a.add(value()); ws();
				char c = s.charAt(i++);
				if (c == ']') return a;
				if (c != ',') throw err(", expected");
			}
		}
		String string() {
			StringBuilder sb = new StringBuilder();
			i++;
			while (true) {
				if (i >= s.length()) throw err("unterminated string");
				char c = s.charAt(i++);
				if (c == '"') return sb.toString();
				if (c == '\\') {
					char e = s.charAt(i++);
					switch (e) {
						case 'n': sb.append('\n'); break; case 'r': sb.append('\r'); break; case 't': sb.append('\t'); break;
						case 'b': sb.append('\b'); break; case 'f': sb.append('\f'); break;
						case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
						default: sb.append(e);
					}
				} else sb.append(c);
			}
		}
		Object number() {
			int st = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
			if (st == i) throw err("unexpected char");
			return Double.valueOf(s.substring(st, i));
		}
	}
}
