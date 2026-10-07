# Interview notes: explain this project confidently

## 60-second pitch
"I wanted to see how raw sales data becomes business decisions. I took a messy sales file, wrote a Java program that validates and cleans it, and loads it into a star-schema MySQL database. Then I wrote SQL views for the KPIs a manager asks about, like revenue, profit, trends, top products and region performance, and built a dashboard on top. On my sample data the analysis showed one region had the lowest margin because of heavy discounting, and that lines discounted over 20% lost money overall. I also wrote automated tests that check the pipeline against an independent calculation."

## Questions you should be able to answer (answers are in this project)
1. **Why a star schema, not one big table?** Facts (measurable events) are separated from descriptive dimensions. Queries stay simple and fast, names are stored once, and it is how data warehouses such as SAP BW are modelled.
2. **What does the Java code do with bad rows?** Applies ordered rules (`Cleaner.java`), rejects with a reason into `rejects.csv`, never silently drops. Duplicates: first valid row wins.
3. **Why BigDecimal for money?** `double` cannot represent 0.1 exactly; totals drift. BigDecimal with HALF_UP gives exact cents.
4. **How is month-over-month growth calculated?** `LAG(revenue)` gives the previous month; growth = (this − previous) / previous × 100. First month is NULL.
5. **Why views for KPIs?** One definition of each KPI, reused by every tool (Power BI, the dashboard, ad-hoc queries).
6. **What is `RANK()` vs `ROW_NUMBER()`?** RANK gives ties the same rank and skips numbers; ROW_NUMBER never ties.
7. **OLTP vs OLAP?** OLTP = many small writes (orders being placed). OLAP = few large read-heavy analytic queries; this project is OLAP.
8. **What would you change at 10 million rows?** Index fact foreign keys (already done), load in batches, pre-aggregate monthly summary tables, partition the fact table by date, push cleaning into a staged load.
9. **How did you test it?** Three-way check: Java output vs an independent Python implementation vs the SQL views, plus hand-computed tiny datasets and deliberate bug injection.
10. **What was limited?** Sample data is synthetic; MySQL loader needs a run on a live server; dashboard has no region/date slicers yet.

## Honesty rules
- Say the data is synthetic. Say what you built yourself and what you learned.
- Be ready to rewrite any SQL view on a whiteboard. Practise `v_monthly_trend` and `v_region_performance` until you can.
- ETL link to SAP: extract → transform → load; facts/dimensions ↔ InfoCubes/master data in SAP BW; dashboard ↔ SAP Analytics Cloud story.
