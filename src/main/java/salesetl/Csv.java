package salesetl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC-4180 style CSV reader/writer (quotes, escaped quotes, embedded newlines, CRLF). */
public final class Csv {
    private Csv() {}

    public static List<String[]> read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1); // strip BOM
        return parse(text);
    }

    public static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else field.append(c);
            } else if (c == '"') { inQuotes = true; any = true; }
            else if (c == ',') { row.add(field.toString()); field.setLength(0); any = true; }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || field.length() > 0) { row.add(field.toString()); rows.add(row.toArray(new String[0])); }
                row = new ArrayList<>(); field.setLength(0); any = false;
            } else { field.append(c); any = true; }
        }
        if (any || field.length() > 0) { row.add(field.toString()); rows.add(row.toArray(new String[0])); }
        return rows;
    }

    public static String escape(String s) {
        boolean needs = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return needs ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    public static String line(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) { if (i > 0) sb.append(','); sb.append(escape(fields[i])); }
        return sb.toString();
    }

    public static void write(Path path, String[] header, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder(line(header)).append('\n');
        for (String[] r : rows) sb.append(line(r)).append('\n');
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
