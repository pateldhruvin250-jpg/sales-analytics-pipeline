package salesetl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Usage: java salesetl.Main [--input data/raw_sales.csv] [--out out] [--sql sql]
 *        [--mysql-url jdbc:mysql://localhost:3306/sales --user root --password secret]
 * Password may also come from the DB_PASSWORD environment variable.
 */
public final class Main {
    public record Summary(int rowsRead, int cleanRows, int rejectedRows, Map<String, Integer> rejectReasons, StarSchema schema) {}

    public static Summary run(Path input, Path outDir) throws IOException {
        List<String[]> rows = Csv.read(input);
        Cleaner.Result result = Cleaner.clean(rows);
        StarSchema schema = StarSchema.build(result.clean());
        schema.writeTo(outDir);
        List<String[]> rej = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (Cleaner.Rejected r : result.rejected()) {
            rej.add(new String[]{Integer.toString(r.rowNumber()), r.reason(), r.raw()});
            reasons.merge(r.reason(), 1, Integer::sum);
        }
        Csv.write(outDir.resolve("rejects.csv"), new String[]{"row_number", "reason", "raw_row"}, rej);
        return new Summary(rows.size() - 1, result.clean().size(), rej.size(), reasons, schema);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>(Map.of("--input", "data/raw_sales.csv", "--out", "out", "--sql", "sql"));
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i], args[i + 1]);
        Summary s = run(Path.of(opt.get("--input")), Path.of(opt.get("--out")));
        System.out.printf("Read %d rows -> %d clean, %d rejected %s%n", s.rowsRead(), s.cleanRows(), s.rejectedRows(), s.rejectReasons());
        System.out.println("Star-schema CSVs and rejects.csv written to " + opt.get("--out"));
        if (opt.containsKey("--mysql-url")) {
            String pw = opt.getOrDefault("--password", System.getenv().getOrDefault("DB_PASSWORD", ""));
            MySqlLoader.load(opt.get("--mysql-url"), opt.getOrDefault("--user", "root"), pw, Path.of(opt.get("--sql")), s.schema());
            System.out.println("Loaded into MySQL and created KPI views.");
        }
    }
}
