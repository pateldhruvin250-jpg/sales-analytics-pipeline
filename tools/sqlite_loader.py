"""Load the Java ETL output (out/*.csv) into SQLite and apply the same schema and KPI views used for MySQL.
Used by the automated tests and by build_dashboard_data.py so everything works without a MySQL server."""
import csv
import pathlib
import sqlite3

TABLES = ["dim_customer", "dim_product", "dim_region", "dim_date", "fact_orders"]


def load(out_dir, sql_dir, conn=None):
    out_dir, sql_dir = pathlib.Path(out_dir), pathlib.Path(sql_dir)
    conn = conn or sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript((sql_dir / "schema.sql").read_text())
    for table in TABLES:
        with (out_dir / f"{table}.csv").open(newline="", encoding="utf-8") as f:
            reader = csv.reader(f)
            cols = next(reader)
            marks = ",".join("?" * len(cols))
            conn.executemany(f"INSERT INTO {table} ({','.join(cols)}) VALUES ({marks})", reader)
    conn.commit()
    conn.executescript((sql_dir / "kpi_views.sql").read_text())
    return conn
