-- Star schema. Portable across MySQL 8+ and SQLite (used by the automated tests).
DROP TABLE IF EXISTS fact_orders;
DROP TABLE IF EXISTS dim_customer;
DROP TABLE IF EXISTS dim_product;
DROP TABLE IF EXISTS dim_region;
DROP TABLE IF EXISTS dim_date;

CREATE TABLE dim_customer (
  customer_key  INT PRIMARY KEY,
  customer_id   VARCHAR(30)  NOT NULL UNIQUE,
  customer_name VARCHAR(120) NOT NULL,
  segment       VARCHAR(40)  NOT NULL
);

CREATE TABLE dim_product (
  product_key  INT PRIMARY KEY,
  product_id   VARCHAR(30)  NOT NULL UNIQUE,
  product_name VARCHAR(120) NOT NULL,
  category     VARCHAR(60)  NOT NULL
);

CREATE TABLE dim_region (
  region_key  INT PRIMARY KEY,
  region_name VARCHAR(40) NOT NULL UNIQUE
);

CREATE TABLE dim_date (
  date_key     INT PRIMARY KEY,          -- yyyymmdd
  full_date    DATE NOT NULL,
  year_num     INT NOT NULL,
  quarter_num  INT NOT NULL,
  month_num    INT NOT NULL,
  month_label  VARCHAR(7) NOT NULL,      -- yyyy-mm
  week_label   VARCHAR(8) NOT NULL       -- ISO week, yyyy-Www
);

CREATE TABLE fact_orders (
  order_line_key INT PRIMARY KEY,
  order_id       VARCHAR(30) NOT NULL,
  date_key       INT NOT NULL,
  customer_key   INT NOT NULL,
  product_key    INT NOT NULL,
  region_key     INT NOT NULL,
  quantity       INT NOT NULL,
  unit_price     DECIMAL(12,2) NOT NULL,
  discount       DECIMAL(5,4)  NOT NULL,
  revenue        DECIMAL(14,2) NOT NULL,  -- quantity * unit_price * (1 - discount)
  cost           DECIMAL(14,2) NOT NULL,  -- quantity * unit cost
  profit         DECIMAL(14,2) NOT NULL,  -- revenue - cost
  FOREIGN KEY (date_key)     REFERENCES dim_date(date_key),
  FOREIGN KEY (customer_key) REFERENCES dim_customer(customer_key),
  FOREIGN KEY (product_key)  REFERENCES dim_product(product_key),
  FOREIGN KEY (region_key)   REFERENCES dim_region(region_key),
  UNIQUE (order_id, product_key)
);

CREATE INDEX idx_fact_date     ON fact_orders(date_key);
CREATE INDEX idx_fact_customer ON fact_orders(customer_key);
CREATE INDEX idx_fact_product  ON fact_orders(product_key);
CREATE INDEX idx_fact_region   ON fact_orders(region_key);
