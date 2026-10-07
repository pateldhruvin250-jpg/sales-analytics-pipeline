"""Independent re-implementation of the cleaning rules and KPI maths, written separately from the Java code
and the SQL, so the pipeline is cross-checked three ways (Java vs Python vs SQL)."""
import csv
import datetime as dt
from collections import defaultdict
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

CENTS = Decimal("0.01")
REQUIRED_TEXT = ["order_id", "customer_id", "customer_name", "product_id", "product_name", "category", "region"]


class Reject(Exception):
    pass


def _date(s):
    for fmt in ("%Y-%m-%d", "%d/%m/%Y"):
        try:
            return dt.datetime.strptime(s, fmt).date()
        except ValueError:
            pass
    raise Reject("bad_date")


def _dec(s, reason):
    try:
        d = Decimal(s)
    except InvalidOperation:
        raise Reject(reason)
    if not d.is_finite():
        raise Reject(reason)
    return d


def _row(r):
    r = {k.strip().lower(): (v or "").strip() for k, v in r.items()}
    r["region"] = " ".join(w.capitalize() for w in r["region"].split())
    if any(not r[c] for c in REQUIRED_TEXT):
        raise Reject("missing_field")
    date = _date(r["order_date"])
    try:
        qty = int(r["quantity"])
    except ValueError:
        raise Reject("bad_quantity")
    if qty <= 0:
        raise Reject("bad_quantity")
    price = _dec(r["unit_price"], "bad_price")
    if price <= 0:
        raise Reject("bad_price")
    disc = Decimal(0) if r["discount"] == "" else _dec(r["discount"], "bad_discount")
    if disc < 0 or disc > 1:
        raise Reject("bad_discount")
    cost = _dec(r["cost"], "bad_cost")
    if cost < 0:
        raise Reject("bad_cost")
    revenue = (price * qty * (1 - disc)).quantize(CENTS, ROUND_HALF_UP)
    total_cost = (cost * qty).quantize(CENTS, ROUND_HALF_UP)
    return dict(order_id=r["order_id"], date=date, customer=r["customer_name"], customer_id=r["customer_id"],
                product=r["product_name"], product_id=r["product_id"], category=r["category"], region=r["region"],
                qty=qty, discount=disc, revenue=revenue, profit=revenue - total_cost)


def clean(path):
    good, reasons, seen = [], defaultdict(int), set()
    with open(path, newline="", encoding="utf-8") as f:
        for raw in csv.DictReader(f):
            try:
                rec = _row(raw)
            except Reject as e:
                reasons[str(e)] += 1
                continue
            key = (rec["order_id"], rec["product_id"])
            if key in seen:
                reasons["duplicate"] += 1
            else:
                seen.add(key)
                good.append(rec)
    return good, dict(reasons)


def group(records, keyfn):
    out = defaultdict(lambda: {"revenue": Decimal(0), "profit": Decimal(0), "orders": set(), "lines": 0, "units": 0})
    for r in records:
        g = out[keyfn(r)]
        g["revenue"] += r["revenue"]; g["profit"] += r["profit"]
        g["orders"].add(r["order_id"]); g["lines"] += 1; g["units"] += r["qty"]
    return out


def band(d):
    return "No discount" if d == 0 else "1-10%" if d <= Decimal("0.10") else "11-20%" if d <= Decimal("0.20") else "Over 20%"
