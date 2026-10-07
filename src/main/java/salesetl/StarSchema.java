package salesetl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;

/** Builds dimension and fact rows (surrogate keys by first appearance) from clean records. */
public final class StarSchema {
    public static final String[] CUSTOMER_COLS = {"customer_key", "customer_id", "customer_name", "segment"};
    public static final String[] PRODUCT_COLS = {"product_key", "product_id", "product_name", "category"};
    public static final String[] REGION_COLS = {"region_key", "region_name"};
    public static final String[] DATE_COLS = {"date_key", "full_date", "year_num", "quarter_num", "month_num", "month_label", "week_label"};
    public static final String[] FACT_COLS = {"order_line_key", "order_id", "date_key", "customer_key", "product_key",
            "region_key", "quantity", "unit_price", "discount", "revenue", "cost", "profit"};

    public final List<String[]> customers = new ArrayList<>(), products = new ArrayList<>(),
            regions = new ArrayList<>(), dates = new ArrayList<>(), facts = new ArrayList<>();

    public static StarSchema build(List<SalesRecord> records) {
        StarSchema s = new StarSchema();
        Map<String, Integer> ck = new HashMap<>(), pk = new HashMap<>(), rk = new HashMap<>();
        TreeMap<Integer, String[]> dateRows = new TreeMap<>();
        int line = 0;
        for (SalesRecord r : records) {
            int c = ck.computeIfAbsent(r.customerId(), k -> {
                s.customers.add(new String[]{str(s.customers.size() + 1), r.customerId(), r.customerName(), r.segment()});
                return s.customers.size();
            });
            int p = pk.computeIfAbsent(r.productId(), k -> {
                s.products.add(new String[]{str(s.products.size() + 1), r.productId(), r.productName(), r.category()});
                return s.products.size();
            });
            int g = rk.computeIfAbsent(r.region(), k -> {
                s.regions.add(new String[]{str(s.regions.size() + 1), r.region()});
                return s.regions.size();
            });
            LocalDate d = r.orderDate();
            int dateKey = d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
            dateRows.computeIfAbsent(dateKey, k -> new String[]{str(dateKey), d.toString(), str(d.getYear()),
                    str(d.get(IsoFields.QUARTER_OF_YEAR)), str(d.getMonthValue()),
                    String.format("%04d-%02d", d.getYear(), d.getMonthValue()),
                    String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))});
            s.facts.add(new String[]{str(++line), r.orderId(), str(dateKey), str(c), str(p), str(g), str(r.quantity()),
                    r.unitPrice().toPlainString(), r.discount().toPlainString(), r.revenue().toPlainString(),
                    r.totalCost().toPlainString(), r.profit().toPlainString()});
        }
        s.dates.addAll(dateRows.values());
        return s;
    }

    private static String str(int v) { return Integer.toString(v); }

    public void writeTo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Csv.write(dir.resolve("dim_customer.csv"), CUSTOMER_COLS, customers);
        Csv.write(dir.resolve("dim_product.csv"), PRODUCT_COLS, products);
        Csv.write(dir.resolve("dim_region.csv"), REGION_COLS, regions);
        Csv.write(dir.resolve("dim_date.csv"), DATE_COLS, dates);
        Csv.write(dir.resolve("fact_orders.csv"), FACT_COLS, facts);
    }
}
