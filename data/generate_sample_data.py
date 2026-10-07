"""Generate data/raw_sales.csv: realistic sales rows plus deliberately dirty rows.

Deterministic (seed 42). To use a real dataset (e.g. Superstore), rename its
columns to the header used below and replace raw_sales.csv.
"""
import csv
import datetime as dt
import pathlib
import random

random.seed(42)
REGIONS = ["East", "West", "Central", "South"]
CATALOG = {
    "Furniture": [("Office Chair", 120, 80), ("Desk", 300, 210), ("Bookcase", 150, 100), ("Standing Mat", 40, 25)],
    "Technology": [("Laptop", 900, 720), ("Monitor", 250, 180), ("Keyboard", 45, 28), ("Headset", 80, 50), ("Webcam", 60, 38)],
    "Office Supplies": [("Notebook Pack", 12, 6), ("Printer Paper", 25, 15), ("Stapler", 15, 8), ("Marker Set", 10, 5), ("Binder", 8, 4)],
}
products, n = [], 1
for cat, items in CATALOG.items():
    for name, price, cost in items:
        products.append((f"P{n:03d}", name, cat, price, cost))
        n += 1
FIRST = ["Aarav", "Diya", "Kabir", "Meera", "Rohan", "Isha", "Vihaan", "Anaya", "Arjun", "Sara", "Neel", "Riya"]
LAST = ["Patel", "Shah", "Mehta", "Desai", "Joshi", "Trivedi", "Modi", "Parikh", "Bhatt", "Thakkar"]
customers = [
    (f"C{i:03d}", f"{random.choice(FIRST)} {random.choice(LAST)}",
     random.choice(["Consumer", "Corporate", "Home Office"]), random.choice(REGIONS))
    for i in range(1, 81)
]
# West over-discounts on purpose so the analysis has a real story to tell.
DISCOUNTS = {"West": [0.1, 0.2, 0.3, 0.3, 0.2], "default": [0, 0, 0, 0.05, 0.1, 0.2]}

HEADER = ["order_id", "order_date", "customer_id", "customer_name", "segment", "product_id",
          "product_name", "category", "region", "quantity", "unit_price", "discount", "cost"]
rows = []
start = dt.date(2023, 1, 1)
for o in range(1, 901):
    d = start + dt.timedelta(days=random.randrange(730))
    if d.month >= 10 and random.random() < 0.5:  # Q4 seasonality
        d = d.replace(day=random.randint(1, 28))
    cid, cname, seg, region = random.choice(customers)
    for pid, pname, cat, price, cost in random.sample(products, random.randint(1, 4)):
        disc = random.choice(DISCOUNTS.get(region, DISCOUNTS["default"]))
        p = round(price * random.uniform(0.95, 1.05), 2)
        rows.append([f"O{o:05d}", d.isoformat(), cid, cname, seg, pid, pname, cat, region,
                     random.randint(1, 6), f"{p:.2f}", disc, cost])

clean_n = len(rows)


def pick(k):
    return random.sample(range(clean_n), k)


dirty = []


def add(idxs, fn):
    for i in idxs:
        r = list(rows[i])
        r[0] = f"X{len(dirty):05d}"  # fresh order id so these never collide with good rows
        fn(r)
        dirty.append(r)


dups = [list(rows[i]) for i in pick(25)]  # exact duplicates of good rows
add(pick(15), lambda r: r.__setitem__(2, ""))  # missing customer id
add(pick(8), lambda r: r.__setitem__(1, "31/02/2023"))  # impossible date
add(pick(7), lambda r: r.__setitem__(1, "not a date"))
add(pick(10), lambda r: r.__setitem__(9, "-2"))  # negative quantity
add(pick(10), lambda r: r.__setitem__(11, "1.5"))  # discount above 100%
add(pick(8), lambda r: r.__setitem__(10, "N/A"))  # non-numeric price
add(pick(10), lambda r: r.__setitem__(1, dt.date.fromisoformat(r[1]).strftime("%d/%m/%Y")))  # valid, other format
add(pick(10), lambda r: r.__setitem__(8, "  " + r[8].upper() + " "))  # messy region text
add(pick(8), lambda r: r.__setitem__(11, ""))  # blank discount -> 0

rows.extend(dups)
rows.extend(dirty)

out = pathlib.Path(__file__).with_name("raw_sales.csv")
with out.open("w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(HEADER)
    w.writerows(rows)
print(f"wrote {out} ({len(rows)} data rows)")
