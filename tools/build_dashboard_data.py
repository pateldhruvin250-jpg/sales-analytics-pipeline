"""Query the KPI views and write dashboard/data.js (demo mode, no MySQL needed).
Usage: python3 tools/build_dashboard_data.py [out_dir] [sql_dir] [dashboard_dir]"""
import csv
import json
import pathlib
import sys
from collections import Counter

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from sqlite_loader import load  # noqa: E402

QUERIES = {
    "kpi": "SELECT * FROM v_kpi_summary",
    "monthly": "SELECT * FROM v_monthly_trend ORDER BY month_label",
    "weekly": "SELECT * FROM v_weekly_trend ORDER BY week_label",
    "top_products": "SELECT * FROM v_top_products ORDER BY revenue_rank, product_name LIMIT 10",
    "top_customers": "SELECT * FROM v_top_customers ORDER BY revenue_rank, customer_name LIMIT 10",
    "regions": "SELECT * FROM v_region_performance ORDER BY revenue DESC",
    "categories": "SELECT * FROM v_category_profit ORDER BY revenue DESC",
    "discounts": "SELECT * FROM v_discount_vs_profit ORDER BY band_order",
}


def build(out_dir="out", sql_dir="sql", dash_dir="dashboard"):
    conn = load(out_dir, sql_dir)
    conn.row_factory = lambda cur, row: {d[0]: v for d, v in zip(cur.description, row)}
    data = {name: conn.execute(q).fetchall() for name, q in QUERIES.items()}
    data["kpi"] = data["kpi"][0]
    with (pathlib.Path(out_dir) / "rejects.csv").open(newline="", encoding="utf-8") as f:
        reasons = Counter(r["reason"] for r in csv.DictReader(f))
    data["quality"] = {"rejected": sum(reasons.values()), "reasons": dict(reasons),
                       "loaded": conn.execute("SELECT COUNT(*) AS n FROM fact_orders").fetchone()["n"]}
    path = pathlib.Path(dash_dir) / "data.js"
    path.write_text("window.DASHBOARD = " + json.dumps(data, indent=1) + ";\n", encoding="utf-8")
    return data, path


if __name__ == "__main__":
    args = sys.argv[1:]
    _, p = build(*args)
    print(f"wrote {p}")
