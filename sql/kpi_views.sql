-- KPI views. Portable across MySQL 8+ and SQLite 3.25+. Consumers add their own ORDER BY / LIMIT.

DROP VIEW IF EXISTS v_kpi_summary;
CREATE VIEW v_kpi_summary AS
SELECT ROUND(SUM(revenue), 2)                          AS total_revenue,
       ROUND(SUM(profit), 2)                           AS total_profit,
       ROUND(100.0 * SUM(profit) / SUM(revenue), 2)    AS profit_margin_pct,
       COUNT(DISTINCT order_id)                        AS total_orders,
       COUNT(DISTINCT customer_key)                    AS total_customers,
       ROUND(SUM(revenue) / COUNT(DISTINCT order_id), 2) AS avg_order_value
FROM fact_orders;

DROP VIEW IF EXISTS v_monthly_trend;
CREATE VIEW v_monthly_trend AS
WITH monthly AS (
  SELECT d.month_label,
         ROUND(SUM(f.revenue), 2)      AS revenue,
         ROUND(SUM(f.profit), 2)       AS profit,
         COUNT(DISTINCT f.order_id)    AS orders
  FROM fact_orders f JOIN dim_date d ON d.date_key = f.date_key
  GROUP BY d.month_label
)
SELECT month_label, revenue, profit, orders,
       ROUND(100.0 * (revenue - LAG(revenue) OVER (ORDER BY month_label))
                   / LAG(revenue) OVER (ORDER BY month_label), 2) AS revenue_mom_pct,
       ROUND(SUM(revenue) OVER (ORDER BY month_label), 2)         AS running_revenue
FROM monthly;

DROP VIEW IF EXISTS v_weekly_trend;
CREATE VIEW v_weekly_trend AS
SELECT d.week_label,
       ROUND(SUM(f.revenue), 2)   AS revenue,
       ROUND(SUM(f.profit), 2)    AS profit,
       COUNT(DISTINCT f.order_id) AS orders
FROM fact_orders f JOIN dim_date d ON d.date_key = f.date_key
GROUP BY d.week_label;

DROP VIEW IF EXISTS v_top_products;
CREATE VIEW v_top_products AS
SELECT RANK() OVER (ORDER BY SUM(f.revenue) DESC) AS revenue_rank,
       p.product_name, p.category,
       SUM(f.quantity)             AS units,
       ROUND(SUM(f.revenue), 2)    AS revenue,
       ROUND(SUM(f.profit), 2)     AS profit
FROM fact_orders f JOIN dim_product p ON p.product_key = f.product_key
GROUP BY p.product_key, p.product_name, p.category;

DROP VIEW IF EXISTS v_top_customers;
CREATE VIEW v_top_customers AS
SELECT RANK() OVER (ORDER BY SUM(f.revenue) DESC) AS revenue_rank,
       c.customer_name, c.segment,
       COUNT(DISTINCT f.order_id)  AS orders,
       ROUND(SUM(f.revenue), 2)    AS revenue,
       ROUND(SUM(f.profit), 2)     AS profit
FROM fact_orders f JOIN dim_customer c ON c.customer_key = f.customer_key
GROUP BY c.customer_key, c.customer_name, c.segment;

DROP VIEW IF EXISTS v_region_performance;
CREATE VIEW v_region_performance AS
SELECT r.region_name,
       ROUND(SUM(f.revenue), 2)                                          AS revenue,
       ROUND(SUM(f.profit), 2)                                           AS profit,
       ROUND(100.0 * SUM(f.profit) / SUM(f.revenue), 2)                  AS profit_margin_pct,
       ROUND(100.0 * AVG(f.discount), 2)                                 AS avg_discount_pct,
       ROUND(100.0 * SUM(f.revenue) / SUM(SUM(f.revenue)) OVER (), 2)    AS revenue_share_pct
FROM fact_orders f JOIN dim_region r ON r.region_key = f.region_key
GROUP BY r.region_key, r.region_name;

DROP VIEW IF EXISTS v_category_profit;
CREATE VIEW v_category_profit AS
SELECT p.category,
       ROUND(SUM(f.revenue), 2)                         AS revenue,
       ROUND(SUM(f.profit), 2)                          AS profit,
       ROUND(100.0 * SUM(f.profit) / SUM(f.revenue), 2) AS profit_margin_pct
FROM fact_orders f JOIN dim_product p ON p.product_key = f.product_key
GROUP BY p.category;

DROP VIEW IF EXISTS v_discount_vs_profit;
CREATE VIEW v_discount_vs_profit AS
SELECT band_order, discount_band,
       COUNT(*)                                         AS order_lines,
       ROUND(SUM(revenue), 2)                           AS revenue,
       ROUND(SUM(profit), 2)                            AS profit,
       ROUND(100.0 * SUM(profit) / SUM(revenue), 2)     AS profit_margin_pct
FROM (
  SELECT revenue, profit,
         CASE WHEN discount = 0    THEN 1
              WHEN discount <= 0.10 THEN 2
              WHEN discount <= 0.20 THEN 3
              ELSE 4 END AS band_order,
         CASE WHEN discount = 0    THEN 'No discount'
              WHEN discount <= 0.10 THEN '1-10%'
              WHEN discount <= 0.20 THEN '11-20%'
              ELSE 'Over 20%' END AS discount_band
  FROM fact_orders
) banded
GROUP BY band_order, discount_band;
