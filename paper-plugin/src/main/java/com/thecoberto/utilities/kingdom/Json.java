package com.thecoberto.utilities.kingdom;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Just enough JSON for Discord webhooks: writing maps and lists, and reading a message's id. */
final class Json {
	private Json() {}

	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(out, value);
		return out.toString();
	}

	private static void write(StringBuilder out, Object value) {
		if (value == null) out.append("null");
		else if (value instanceof String s) string(out, s);
		else if (value instanceof Number || value instanceof Boolean) out.append(value);
		else if (value instanceof Map<?, ?> map) {
			out.append('{');
			Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<?, ?> e = it.next();
				string(out, String.valueOf(e.getKey()));
				out.append(':');
				write(out, e.getValue());
				if (it.hasNext()) out.append(',');
			}
			out.append('}');
		} else if (value instanceof List<?> list) {
			out.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) out.append(',');
				write(out, list.get(i));
			}
			out.append(']');
		} else string(out, value.toString());
	}

	private static void string(StringBuilder out, String s) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		out.append('"');
	}

	/** The top-level "id" of a JSON object (a message's own id, not its author's or an embed's). */
	static String topLevelId(String json) {
		int depth = 0;
		boolean inString = false;
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (inString) {
				if (c == '\\') i++;
				else if (c == '"') inString = false;
				continue;
			}
			if (c == '{' || c == '[') depth++;
			else if (c == '}' || c == ']') depth--;
			else if (c == '"') {
				if (depth == 1 && json.startsWith("\"id\"", i)) {
					int start = json.indexOf('"', json.indexOf(':', i + 4) + 1);
					int end = json.indexOf('"', start + 1);
					if (start > 0 && end > start) return json.substring(start + 1, end);
				}
				inString = true;
			}
		}
		return null;
	}
}
