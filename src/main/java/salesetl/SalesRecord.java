package salesetl;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One validated, cleaned order line. Money values use BigDecimal (no floating-point drift). */
public record SalesRecord(String orderId, LocalDate orderDate, String customerId, String customerName,
                          String segment, String productId, String productName, String category,
                          String region, int quantity, BigDecimal unitPrice, BigDecimal discount,
                          BigDecimal totalCost, BigDecimal revenue, BigDecimal profit) {}
