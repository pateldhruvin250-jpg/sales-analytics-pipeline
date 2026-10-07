package salesetl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Loads the star schema into MySQL 8+ (needs the MySQL JDBC driver jar on the classpath). */
public final class MySqlLoader {
    private MySqlLoader() {}

    public static void load(String url, String user, String password, Path sqlDir, StarSchema s) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                for (String sql : split(Files.readString(sqlDir.resolve("schema.sql")))) st.execute(sql);
            }
            c.setAutoCommit(false);
            insert(c, "dim_customer", StarSchema.CUSTOMER_COLS, s.customers);
            insert(c, "dim_product", StarSchema.PRODUCT_COLS, s.products);
            insert(c, "dim_region", StarSchema.REGION_COLS, s.regions);
            insert(c, "dim_date", StarSchema.DATE_COLS, s.dates);
            insert(c, "fact_orders", StarSchema.FACT_COLS, s.facts);
            c.commit();
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                for (String sql : split(Files.readString(sqlDir.resolve("kpi_views.sql")))) st.execute(sql);
            }
        }
    }

    static List<String> split(String script) {
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\n")) if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        List<String> out = new ArrayList<>();
        for (String part : sb.toString().split(";")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    private static void insert(Connection c, String table, String[] cols, List<String[]> rows) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES ("
                + String.join(",", Collections.nCopies(cols.length, "?")) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int n = 0;
            for (String[] r : rows) {
                for (int i = 0; i < r.length; i++) ps.setString(i + 1, r[i]);
                ps.addBatch();
                if (++n % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }
}
