"""End-to-end tests: raw CSV -> Java ETL -> star-schema CSVs -> SQL schema + KPI views (SQLite) -> dashboard data.
Run via ./run_all_tests.sh (compiles the Java first)."""
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from decimal import Decimal

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tests"))
import build_dashboard_data  # noqa: E402
import reference as ref  # noqa: E402
from sqlite_loader import load  # noqa: E402

RAW = ROOT / "data" / "raw_sales.csv"
TOL = 0.011  # money tolerance: SQLite stores DECIMAL as REAL


def run_java(raw, out):
    subprocess.run(["java", "-cp", str(ROOT / "build" / "classes"), "salesetl.Main", "--input", str(raw), "--out", str(out)],
                   check=True, capture_output=True, cwd=ROOT)


class PipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = pathlib.Path(cls.tmp.name) / "out"
        run_java(RAW, cls.out)
        cls.db = load(cls.out, ROOT / "sql")
        cls.good, cls.reasons = ref.clean(RAW)
        cls.total_rev = sum(r["revenue"] for r in cls.good)
        cls.total_profit = sum(r["profit"] for r in cls.good)

    @classmethod
    def tearDownClass(cls):
        cls.db.close(); cls.tmp.cleanup()

    def q(self, sql):
        return self.db.execute(sql).fetchall()

    def one(self, sql):
        return self.db.execute(sql).fetchone()[0]

    # ---- data quality / ETL ----
    def test_clean_and_reject_counts_match_reference(self):
        import csv
        with (self.out / "rejects.csv").open(newline="", encoding="utf-8") as f:
            java_reasons = dict(Counter(r["reason"] for r in csv.DictReader(f)))
        self.assertEqual(java_reasons, self.reasons)
        self.assertEqual(self.one("SELECT COUNT(*) FROM fact_orders"), len(self.good))

    def test_expected_dirty_rows_were_caught(self):
        self.assertEqual(self.reasons, {"duplicate": 25, "missing_field": 15, "bad_date": 15,
                                        "bad_quantity": 10, "bad_discount": 10, "bad_price": 8})

    def test_valid_but_messy_rows_survived(self):
        self.assertEqual(self.one("SELECT COUNT(*) FROM dim_region"), 4)  # messy ' EAST ' etc. merged into 4 regions
        self.assertEqual(self.one("SELECT COUNT(*) FROM fact_orders WHERE discount = 0"),
                         sum(1 for r in self.good if r["discount"] == 0))

    def test_no_invalid_values_in_fact(self):
        self.assertEqual(self.one("SELECT COUNT(*) FROM fact_orders WHERE quantity <= 0 OR unit_price <= 0 "
                                  "OR discount < 0 OR discount > 1 OR cost < 0"), 0)

    def test_referential_integrity(self):
        for dim, key in [("dim_date", "date_key"), ("dim_customer", "customer_key"),
                         ("dim_product", "product_key"), ("dim_region", "region_key")]:
            orphans = self.one(f"SELECT COUNT(*) FROM fact_orders f LEFT JOIN {dim} d ON d.{key}=f.{key} WHERE d.{key} IS NULL")
            self.assertEqual(orphans, 0, dim)
        self.assertEqual(self.q("PRAGMA foreign_key_check"), [])

    def test_no_duplicate_order_lines(self):
        self.assertEqual(self.one("SELECT COUNT(*) FROM (SELECT order_id, product_key FROM fact_orders "
                                  "GROUP BY 1,2 HAVING COUNT(*)>1)"), 0)

    def test_revenue_and_profit_formulas_in_db(self):
        self.assertEqual(self.one("SELECT COUNT(*) FROM fact_orders WHERE ABS(revenue - quantity*unit_price*(1-discount)) > 0.0051"), 0)
        self.assertEqual(self.one("SELECT COUNT(*) FROM fact_orders WHERE ABS(profit - (revenue - cost)) > 0.0051"), 0)

    def test_dimension_attributes_unique_and_complete(self):
        self.assertEqual(self.one("SELECT COUNT(*) FROM dim_customer"), len({r["customer_id"] for r in self.good}))
        self.assertEqual(self.one("SELECT COUNT(*) FROM dim_product"), len({r["product_id"] for r in self.good}))
        self.assertEqual(self.one("SELECT COUNT(*) FROM dim_date"), len({r["date"] for r in self.good}))

    def test_schema_rerun_is_idempotent(self):
        db2 = load(self.out, ROOT / "sql")
        load(self.out, ROOT / "sql", conn=db2)  # second load on the same connection drops and rebuilds
        self.assertEqual(db2.execute("SELECT COUNT(*) FROM fact_orders").fetchone()[0], len(self.good))
        db2.close()

    # ---- KPI views ----
    def test_kpi_summary(self):
        rev, prof, margin, orders, custs, aov = self.q("SELECT * FROM v_kpi_summary")[0]
        n_orders = len({r["order_id"] for r in self.good})
        self.assertAlmostEqual(rev, float(self.total_rev), delta=TOL)
        self.assertAlmostEqual(prof, float(self.total_profit), delta=TOL)
        self.assertAlmostEqual(margin, float(100 * self.total_profit / self.total_rev), delta=0.011)
        self.assertEqual(orders, n_orders)
        self.assertEqual(custs, len({r["customer_id"] for r in self.good}))
        self.assertAlmostEqual(aov, float(self.total_rev / n_orders), delta=0.011)

    def test_monthly_trend_matches_reference_and_mom_math(self):
        g = ref.group(self.good, lambda r: r["date"].strftime("%Y-%m"))
        rows = self.q("SELECT month_label, revenue, profit, orders, revenue_mom_pct, running_revenue "
                      "FROM v_monthly_trend ORDER BY month_label")
        self.assertEqual([r[0] for r in rows], sorted(g))
        prev, running = None, Decimal(0)
        for label, rev, prof, orders, mom, run in rows:
            self.assertAlmostEqual(rev, float(g[label]["revenue"]), delta=TOL)
            self.assertAlmostEqual(prof, float(g[label]["profit"]), delta=TOL)
            self.assertEqual(orders, len(g[label]["orders"]))
            running += g[label]["revenue"]
            self.assertAlmostEqual(run, float(running), delta=0.02)
            if prev is None:
                self.assertIsNone(mom)
            else:
                self.assertAlmostEqual(mom, float(100 * (g[label]["revenue"] - prev) / prev), delta=0.02)
            prev = g[label]["revenue"]
        self.assertAlmostEqual(sum(r[1] for r in rows), float(self.total_rev), delta=0.5)

    def test_weekly_trend_matches_reference(self):
        wk = lambda r: "%d-W%02d" % r["date"].isocalendar()[:2]
        g = ref.group(self.good, wk)
        rows = self.q("SELECT week_label, revenue, orders FROM v_weekly_trend")
        self.assertEqual({r[0] for r in rows}, set(g))
        for label, rev, orders in rows:
            self.assertAlmostEqual(rev, float(g[label]["revenue"]), delta=TOL)
            self.assertEqual(orders, len(g[label]["orders"]))

    def test_top_products(self):
        g = ref.group(self.good, lambda r: r["product"])
        rows = self.q("SELECT revenue_rank, product_name, units, revenue FROM v_top_products ORDER BY revenue_rank, product_name")
        self.assertEqual(len(rows), len(g))
        best = max(v["revenue"] for v in g.values())
        self.assertEqual({r[1] for r in rows if r[0] == 1}, {k for k, v in g.items() if v["revenue"] == best})
        revs = [r[3] for r in rows]
        self.assertEqual(revs, sorted(revs, reverse=True))
        for _, name, units, rev in rows:
            self.assertEqual(units, g[name]["units"])
            self.assertAlmostEqual(rev, float(g[name]["revenue"]), delta=TOL)
        self.assertAlmostEqual(sum(revs), float(self.total_rev), delta=0.5)

    def test_top_customers(self):
        g = ref.group(self.good, lambda r: r["customer_id"])
        names = {r["customer_id"]: r["customer"] for r in reversed(self.good)}  # first name wins
        rows = self.q("SELECT revenue_rank, customer_name, orders, revenue FROM v_top_customers ORDER BY revenue_rank")
        self.assertEqual(len(rows), len(g))
        top_rev = max(v["revenue"] for v in g.values())
        self.assertAlmostEqual(rows[0][3], float(top_rev), delta=TOL)
        self.assertEqual(rows[0][0], 1)
        self.assertAlmostEqual(sum(r[3] for r in rows), float(self.total_rev), delta=0.5)
        self.assertEqual(sorted(r[2] for r in rows), sorted(len(v["orders"]) for v in g.values()))
        self.assertTrue(all(r[1] in names.values() for r in rows))

    def test_region_performance(self):
        g = ref.group(self.good, lambda r: r["region"])
        rows = self.q("SELECT region_name, revenue, profit, profit_margin_pct, avg_discount_pct, revenue_share_pct FROM v_region_performance")
        self.assertEqual({r[0] for r in rows}, set(g))
        for name, rev, prof, margin, avg_disc, share in rows:
            self.assertAlmostEqual(rev, float(g[name]["revenue"]), delta=TOL)
            self.assertAlmostEqual(prof, float(g[name]["profit"]), delta=TOL)
            self.assertAlmostEqual(margin, float(100 * g[name]["profit"] / g[name]["revenue"]), delta=0.011)
            discs = [r["discount"] for r in self.good if r["region"] == name]
            self.assertAlmostEqual(avg_disc, float(100 * sum(discs) / len(discs)), delta=0.011)
        self.assertAlmostEqual(sum(r[5] for r in rows), 100.0, delta=0.05)

    def test_category_profit(self):
        g = ref.group(self.good, lambda r: r["category"])
        for cat, rev, prof, margin in self.q("SELECT * FROM v_category_profit"):
            self.assertAlmostEqual(rev, float(g[cat]["revenue"]), delta=TOL)
            self.assertAlmostEqual(prof, float(g[cat]["profit"]), delta=TOL)

    def test_discount_bands_cover_every_line(self):
        g = ref.group(self.good, lambda r: ref.band(r["discount"]))
        rows = self.q("SELECT band_order, discount_band, order_lines, revenue, profit FROM v_discount_vs_profit ORDER BY band_order")
        self.assertEqual(sum(r[2] for r in rows), len(self.good))
        self.assertEqual([r[0] for r in rows], sorted(r[0] for r in rows))
        for _, bandname, lines, rev, prof in rows:
            self.assertEqual(lines, g[bandname]["lines"], bandname)
            self.assertAlmostEqual(rev, float(g[bandname]["revenue"]), delta=TOL)
            self.assertAlmostEqual(prof, float(g[bandname]["profit"]), delta=TOL)

    def test_sample_data_tells_the_discount_story(self):
        rows = {r[0]: r[1] for r in self.q("SELECT discount_band, profit_margin_pct FROM v_discount_vs_profit")}
        self.assertGreater(rows["No discount"], rows["Over 20%"])
        region = {r[0]: r[1] for r in self.q("SELECT region_name, profit_margin_pct FROM v_region_performance")}
        self.assertEqual(min(region, key=region.get), "West")

    def test_views_are_empty_safe(self):
        empty = load_empty()
        for v in ["v_kpi_summary", "v_monthly_trend", "v_weekly_trend", "v_top_products", "v_top_customers",
                  "v_region_performance", "v_category_profit", "v_discount_vs_profit"]:
            empty.execute(f"SELECT * FROM {v}").fetchall()  # must not raise
        self.assertEqual(empty.execute("SELECT total_revenue FROM v_kpi_summary").fetchone()[0], None)
        empty.close()

    # ---- dashboard data ----
    def test_dashboard_data_js(self):
        with tempfile.TemporaryDirectory() as d:
            data, path = build_dashboard_data.build(self.out, ROOT / "sql", d)
            text = path.read_text()
        self.assertTrue(text.startswith("window.DASHBOARD = ") and text.rstrip().endswith(";"))
        parsed = json.loads(re.sub(r"^window\.DASHBOARD = |;\s*$", "", text))
        for key in ["kpi", "monthly", "weekly", "top_products", "top_customers", "regions", "categories", "discounts", "quality"]:
            self.assertTrue(parsed[key], key)
        self.assertEqual(len(parsed["top_products"]), 10)
        self.assertEqual(parsed["quality"]["loaded"], len(self.good))
        self.assertEqual(parsed["quality"]["rejected"], sum(self.reasons.values()))
        self.assertAlmostEqual(parsed["kpi"]["total_revenue"], float(self.total_rev), delta=TOL)


def load_empty():
    import sqlite3
    conn = sqlite3.connect(":memory:")
    conn.executescript((ROOT / "sql" / "schema.sql").read_text())
    conn.executescript((ROOT / "sql" / "kpi_views.sql").read_text())
    return conn


class EdgeCaseTests(unittest.TestCase):
    """Run the whole pipeline on tiny hand-made files where the answers are known exactly."""

    HEADER = "order_id,order_date,customer_id,customer_name,segment,product_id,product_name,category,region,quantity,unit_price,discount,cost\n"

    def run_case(self, body):
        d = pathlib.Path(tempfile.mkdtemp())
        (d / "in.csv").write_text(self.HEADER + body)
        run_java(d / "in.csv", d / "out")
        return load(d / "out", ROOT / "sql")

    def test_hand_computed_kpis(self):
        db = self.run_case(
            "O1,2024-01-10,C1,Asha,Consumer,P1,Chair,Furniture,East,2,100.00,0.10,60\n"   # rev 180, cost 120, profit 60
            "O1,2024-01-10,C1,Asha,Consumer,P2,Pen,Office Supplies,East,10,5.00,0,2\n"   # rev 50, cost 20, profit 30
            "O2,2024-02-15,C2,Ben,Corporate,P1,Chair,Furniture,West,1,100.00,0.50,60\n"  # rev 50, cost 60, profit -10
            "O2,2024-02-15,C2,Ben,Corporate,P1,Chair,Furniture,West,1,100.00,0.50,60\n"  # duplicate -> dropped
        )
        rev, prof, margin, orders, custs, aov = db.execute("SELECT * FROM v_kpi_summary").fetchone()
        self.assertEqual((rev, prof, orders, custs), (280.0, 80.0, 2, 2))
        self.assertAlmostEqual(margin, 28.57, places=2)
        self.assertAlmostEqual(aov, 140.0)
        monthly = db.execute("SELECT month_label, revenue, revenue_mom_pct FROM v_monthly_trend ORDER BY 1").fetchall()
        self.assertEqual(monthly, [("2024-01", 230.0, None), ("2024-02", 50.0, -78.26)])
        self.assertEqual(db.execute("SELECT region_name, profit FROM v_region_performance ORDER BY profit").fetchall(),
                         [("West", -10.0), ("East", 90.0)])  # negative profit is preserved
        self.assertEqual(db.execute("SELECT product_name FROM v_top_products WHERE revenue_rank=1").fetchall(), [("Chair",)])

    def test_single_row_and_all_rejected(self):
        db = self.run_case("O1,2024-01-10,C1,Asha,Consumer,P1,Chair,Furniture,East,1,10.00,0,4\n")
        self.assertEqual(db.execute("SELECT total_revenue, total_profit FROM v_kpi_summary").fetchone(), (10.0, 6.0))
        self.assertIsNone(db.execute("SELECT revenue_mom_pct FROM v_monthly_trend").fetchone()[0])
        db = self.run_case("O1,bad,C1,Asha,Consumer,P1,Chair,Furniture,East,1,10.00,0,4\n")
        self.assertEqual(db.execute("SELECT COUNT(*) FROM fact_orders").fetchone()[0], 0)

    def test_year_boundary_iso_week_and_sorting(self):
        db = self.run_case(
            "O1,2023-12-31,C1,A,Consumer,P1,X,Cat,East,1,10.00,0,0\n"
            "O2,2024-01-01,C1,A,Consumer,P1,X,Cat,East,1,20.00,0,0\n")
        weeks = db.execute("SELECT week_label FROM v_weekly_trend ORDER BY 1").fetchall()
        self.assertEqual(weeks, [("2023-W52",), ("2024-W01",)])
        months = [r[0] for r in db.execute("SELECT month_label FROM v_monthly_trend ORDER BY 1")]
        self.assertEqual(months, ["2023-12", "2024-01"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
