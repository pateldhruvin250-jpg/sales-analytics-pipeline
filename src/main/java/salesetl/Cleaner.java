package salesetl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

/**
 * Validates and normalises raw rows. Rule order (the first failing rule is the reject reason):
 * missing_field, bad_date, bad_quantity, bad_price, bad_discount, bad_cost, then duplicate
 * (key = order_id + product_id; first valid occurrence wins).
 */
public final class Cleaner {
    private Cleaner() {}

    public record Rejected(int rowNumber, String reason, String raw) {}
    public record Result(List<SalesRecord> clean, List<Rejected> rejected) {}

    static final List<String> REQUIRED = List.of("order_id", "order_date", "customer_id", "customer_name",
            "product_id", "product_name", "category", "region", "quantity", "unit_price", "cost");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT));

    private static final class RowError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        RowError(String reason) { super(reason, null, false, false); }
    }

    public static Result clean(List<String[]> rows) {
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV is empty");
        Map<String, Integer> idx = new HashMap<>();
        String[] header = rows.get(0);
        for (int i = 0; i < header.length; i++) idx.put(header[i].trim().toLowerCase(), i);
        List<String> missing = REQUIRED.stream().filter(c -> !idx.containsKey(c)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing columns: " + missing);

        List<SalesRecord> clean = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            try {
                SalesRecord rec = parse(row, idx);
                if (seen.add(rec.orderId() + "|" + rec.productId())) clean.add(rec);
                else rejected.add(new Rejected(r + 1, "duplicate", Csv.line(row)));
            } catch (RowError e) {
                rejected.add(new Rejected(r + 1, e.getMessage(), Csv.line(row)));
            }
        }
        return new Result(clean, rejected);
    }

    private static String get(String[] row, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        return (i == null || i >= row.length) ? "" : row[i].trim();
    }

    private static SalesRecord parse(String[] row, Map<String, Integer> idx) {
        String orderId = get(row, idx, "order_id"), customerId = get(row, idx, "customer_id");
        String customerName = get(row, idx, "customer_name"), productId = get(row, idx, "product_id");
        String productName = get(row, idx, "product_name"), category = get(row, idx, "category");
        String region = title(get(row, idx, "region"));
        String segment = get(row, idx, "segment");
        for (String v : new String[]{orderId, customerId, customerName, productId, productName, category, region})
            if (v.isEmpty()) throw new RowError("missing_field");
        if (segment.isEmpty()) segment = "Unknown";

        LocalDate date = parseDate(get(row, idx, "order_date"));
        int qty;
        try { qty = Integer.parseInt(get(row, idx, "quantity")); } catch (NumberFormatException e) { throw new RowError("bad_quantity"); }
        if (qty <= 0) throw new RowError("bad_quantity");
        BigDecimal price = decimal(get(row, idx, "unit_price"), "bad_price");
        if (price.signum() <= 0) throw new RowError("bad_price");
        String d = get(row, idx, "discount");
        BigDecimal discount = d.isEmpty() ? BigDecimal.ZERO : decimal(d, "bad_discount");
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.ONE) > 0) throw new RowError("bad_discount");
        BigDecimal unitCost = decimal(get(row, idx, "cost"), "bad_cost");
        if (unitCost.signum() < 0) throw new RowError("bad_cost");

        BigDecimal revenue = price.multiply(BigDecimal.valueOf(qty)).multiply(BigDecimal.ONE.subtract(discount))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCost = unitCost.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        return new SalesRecord(orderId, date, customerId, customerName, segment, productId, productName,
                category, region, qty, price, discount, totalCost, revenue, revenue.subtract(totalCost));
    }

    private static LocalDate parseDate(String s) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(s, f); } catch (DateTimeParseException ignored) { }
        }
        throw new RowError("bad_date");
    }

    private static BigDecimal decimal(String s, String reason) {
        try { return new BigDecimal(s); } catch (NumberFormatException e) { throw new RowError(reason); }
    }

    static String title(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
