package salesetl;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Dependency-free test runner (no JUnit needed). Exit code 1 if any check fails. */
public final class TestRunner {
    private static int passed = 0, failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("  FAIL: " + name); }
    }
    private static void eq(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        if (!ok) name += "  (expected=" + expected + ", actual=" + actual + ")";
        check(name, ok);
    }

    private static final String HEADER = "order_id,order_date,customer_id,customer_name,segment,product_id,product_name,category,region,quantity,unit_price,discount,cost";
    /** Valid base row; overrides are "column=value" pairs. */
    private static String row(String... overrides) {
        String[] cols = HEADER.split(",");
        String[] vals = {"O1", "2024-03-05", "C1", "Asha Shah", "Consumer", "P1", "Laptop", "Technology", "East", "3", "100.00", "0.10", "60"};
        for (String o : overrides) {
            int eqIdx = o.indexOf('=');
            String col = o.substring(0, eqIdx);
            for (int i = 0; i < cols.length; i++) if (cols[i].equals(col)) vals[i] = o.substring(eqIdx + 1);
        }
        return Csv.line(vals);
    }
    private static Cleaner.Result clean(String... dataRows) {
        return Cleaner.clean(Csv.parse(HEADER + "\n" + String.join("\n", dataRows)));
    }
    private static String reason(String... overrides) {
        Cleaner.Result r = clean(row(overrides));
        return r.rejected().isEmpty() ? "ACCEPTED" : r.rejected().get(0).reason();
    }

    static void csvTests() {
        List<String[]> r = Csv.parse("a,b\r\n\"x,y\",\"say \"\"hi\"\"\"\n\n\"line1\nline2\",z\n");
        eq("csv row count (blank line skipped)", 3, r.size());
        eq("csv quoted comma", "x,y", r.get(1)[0]);
        eq("csv escaped quote", "say \"hi\"", r.get(1)[1]);
        eq("csv embedded newline", "line1\nline2", r.get(2)[0]);
        eq("csv no trailing newline", 2, Csv.parse("a,b\n1,2").size());
        eq("csv empty trailing field", 3, Csv.parse("a,b,c\n1,2,").get(1).length);
        String[] tricky = {"plain", "has,comma", "has \"quote\"", "multi\nline", ""};
        eq("csv round trip", List.of(tricky), List.of(Csv.parse(Csv.line(tricky)).get(0)));
    }

    static void cleanerTests() {
        SalesRecord ok = clean(row()).clean().get(0);
        eq("revenue = qty*price*(1-disc)", new BigDecimal("270.00"), ok.revenue());
        eq("profit = revenue - qty*cost", new BigDecimal("90.00"), ok.profit());
        eq("total cost", new BigDecimal("180.00"), ok.totalCost());
        eq("half-up rounding (0.025 -> 0.03)", new BigDecimal("0.03"),
                clean(row("quantity=1", "unit_price=0.05", "discount=0.5", "cost=0")).clean().get(0).revenue());
        eq("blank discount -> 0", BigDecimal.ZERO, clean(row("discount=")).clean().get(0).discount());
        eq("discount 100% allowed, revenue 0", new BigDecimal("0.00"), clean(row("discount=1")).clean().get(0).revenue());
        eq("cost 0 allowed", "ACCEPTED", reason("cost=0"));

        eq("discount 1.5 rejected", "bad_discount", reason("discount=1.5"));
        eq("negative discount rejected", "bad_discount", reason("discount=-0.1"));
        eq("discount text rejected", "bad_discount", reason("discount=abc"));
        for (String q : new String[]{"0", "-2", "abc", "2.5", ""}) eq("bad quantity '" + q + "'", "bad_quantity", reason("quantity=" + q));
        for (String p : new String[]{"N/A", "0", "-5", "NaN", ""}) eq("bad price '" + p + "'", "bad_price", reason("unit_price=" + p));
        eq("negative cost rejected", "bad_cost", reason("cost=-1"));
        for (String d : new String[]{"31/02/2023", "2023-13-01", "2023-02-30", "not a date", ""})
            eq("bad date '" + d + "'", "bad_date", reason("order_date=" + d));
        eq("dd/MM/yyyy accepted", LocalDate.of(2023, 3, 15), clean(row("order_date=15/03/2023")).clean().get(0).orderDate());
        eq("unpadded ISO date accepted", LocalDate.of(2023, 3, 5), clean(row("order_date=2023-3-5")).clean().get(0).orderDate());
        eq("leap day accepted", LocalDate.of(2024, 2, 29), clean(row("order_date=2024-02-29")).clean().get(0).orderDate());
        eq("non-leap 29 Feb rejected", "bad_date", reason("order_date=2023-02-29"));
        eq("missing customer id", "missing_field", reason("customer_id="));
        eq("blank-space region", "missing_field", reason("region=   "));
        eq("missing product name", "missing_field", reason("product_name="));
        eq("region normalised", "East", clean(row("region=  eAST ")).clean().get(0).region());
        eq("multi-word region", "North West", clean(row("region=NORTH   WEST")).clean().get(0).region());
        eq("blank segment -> Unknown", "Unknown", clean(row("segment=")).clean().get(0).segment());
        eq("fields trimmed", "Asha Shah", clean(row("customer_name=  Asha Shah  ")).clean().get(0).customerName());
        eq("check order: bad date wins over bad quantity", "bad_date", reason("order_date=zzz", "quantity=-1"));

        Cleaner.Result dup = clean(row(), row(), row("product_id=P2"));
        eq("duplicate removed (clean)", 2, dup.clean().size());
        eq("duplicate reported", "duplicate", dup.rejected().get(0).reason());
        eq("same order, other product kept", 2, clean(row(), row("product_id=P2")).clean().size());
        Cleaner.Result invalidFirst = clean(row("quantity=-1"), row());
        eq("invalid row does not claim the key", 1, invalid(invalidFirst));
        eq("later valid row kept", 1, invalidFirst.clean().size());
        eq("row numbers start at 2 (header = 1)", 3, clean(row(), row("quantity=0")).rejected().get(0).rowNumber());

        boolean threw = false;
        try { Cleaner.clean(Csv.parse("order_id,region\n1,East")); } catch (IllegalArgumentException e) { threw = e.getMessage().contains("quantity"); }
        check("missing required columns -> IllegalArgumentException", threw);
        threw = false;
        try { Cleaner.clean(List.of()); } catch (IllegalArgumentException e) { threw = true; }
        check("empty file -> IllegalArgumentException", threw);
        eq("case/space-insensitive headers", 1, Cleaner.clean(Csv.parse(HEADER.toUpperCase().replace(",", " , ") + "\n" + row())).clean().size());
        eq("short row does not crash", 1, clean("O9,2024-01-01,C1").rejected().size());
    }
    private static int invalid(Cleaner.Result r) { return r.rejected().size(); }

    static void starTests() {
        List<SalesRecord> recs = clean(
                row("order_id=O1", "order_date=2024-12-30"),
                row("order_id=O1", "product_id=P2", "product_name=Mouse", "order_date=2024-12-30"),
                row("order_id=O2", "customer_id=C2", "region=West", "order_date=2025-01-02")).clean();
        StarSchema s = StarSchema.build(recs);
        eq("customers deduplicated", 2, s.customers.size());
        eq("products deduplicated", 2, s.products.size());
        eq("regions", 2, s.regions.size());
        eq("date rows (distinct days)", 2, s.dates.size());
        eq("fact rows", 3, s.facts.size());
        eq("date key format", "20241230", s.dates.get(0)[0]);
        eq("ISO week crosses year (30 Dec 2024)", "2025-W01", s.dates.get(0)[6]);
        eq("quarter", "4", s.dates.get(0)[3]);
        eq("month label", "2024-12", s.dates.get(0)[5]);
        eq("fact customer key reuse", s.facts.get(0)[3], s.facts.get(1)[3]);
        eq("fact keys are sequential", "3", s.facts.get(2)[0]);
        Set<String> custKeys = new HashSet<>(), prodKeys = new HashSet<>(), regKeys = new HashSet<>(), dateKeys = new HashSet<>();
        s.customers.forEach(r -> custKeys.add(r[0])); s.products.forEach(r -> prodKeys.add(r[0]));
        s.regions.forEach(r -> regKeys.add(r[0])); s.dates.forEach(r -> dateKeys.add(r[0]));
        boolean fk = true;
        for (String[] f : s.facts) fk &= dateKeys.contains(f[2]) && custKeys.contains(f[3]) && prodKeys.contains(f[4]) && regKeys.contains(f[5]);
        check("every fact foreign key exists in its dimension", fk);
        eq("empty input -> empty schema", 0, StarSchema.build(List.of()).facts.size());
    }

    static void sqlSplitTests() {
        List<String> parts = MySqlLoader.split("-- comment; with semicolon\nDROP TABLE a;\n\nCREATE TABLE b (x INT);\n");
        eq("sql split ignores comments and blanks", 2, parts.size());
        eq("sql split content", "DROP TABLE a", parts.get(0));
    }

    static void endToEndTests() throws Exception {
        Path dir = Files.createTempDirectory("etl-test");
        Path in = dir.resolve("in.csv");
        Files.writeString(in, HEADER + "\n" + String.join("\n", row(), row(), row("quantity=0"), row("order_id=O2", "customer_id=C2")) + "\n");
        Main.Summary s = Main.run(in, dir.resolve("out"));
        eq("e2e rows read", 4, s.rowsRead());
        eq("e2e clean", 2, s.cleanRows());
        eq("e2e rejected", 2, s.rejectedRows());
        eq("e2e reasons", Map.of("duplicate", 1, "bad_quantity", 1), s.rejectReasons());
        for (String f : new String[]{"dim_customer", "dim_product", "dim_region", "dim_date", "fact_orders", "rejects"})
            check("e2e wrote " + f + ".csv", Files.exists(dir.resolve("out").resolve(f + ".csv")));
        eq("e2e fact file rows (+header)", 3, Csv.read(dir.resolve("out/fact_orders.csv")).size());
        eq("e2e fact header", String.join(",", StarSchema.FACT_COLS), String.join(",", Csv.read(dir.resolve("out/fact_orders.csv")).get(0)));
        Files.writeString(in, "\uFEFF" + HEADER + "\n" + row() + "\n");
        eq("e2e BOM file handled", 1, Main.run(in, dir.resolve("out2")).cleanRows());
    }

    public static void main(String[] args) throws Exception {
        csvTests(); cleanerTests(); starTests(); sqlSplitTests(); endToEndTests();
        System.out.printf("Java tests: %d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
