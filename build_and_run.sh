#!/usr/bin/env bash
# Demo mode (no MySQL needed): clean data -> star schema CSVs -> KPI views (SQLite) -> dashboard/data.js
set -euo pipefail
cd "$(dirname "$0")"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
mkdir -p build/classes
"${JAVAC[@]}" -d build/classes src/main/java/salesetl/*.java
[ -f data/raw_sales.csv ] || python3 data/generate_sample_data.py
java -cp build/classes salesetl.Main --input data/raw_sales.csv --out out
python3 tools/build_dashboard_data.py out sql dashboard
echo "Open dashboard/index.html in your browser."
