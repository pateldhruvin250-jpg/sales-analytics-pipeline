#!/usr/bin/env bash
# Compile the Java, then run: Java unit tests -> end-to-end/SQL tests (Python) -> dashboard smoke test (Node).
set -euo pipefail
cd "$(dirname "$0")"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
rm -rf build && mkdir -p build/classes build/test-classes
echo "== Compiling =="
"${JAVAC[@]}" -d build/classes src/main/java/salesetl/*.java
"${JAVAC[@]}" -cp build/classes -d build/test-classes src/test/java/salesetl/TestRunner.java
echo "== 1/3 Java unit tests =="
java -cp build/classes:build/test-classes salesetl.TestRunner
echo "== 2/3 Pipeline + SQL KPI tests =="
python3 data/generate_sample_data.py >/dev/null
python3 -m unittest tests.test_pipeline -v 2>&1 | tail -n 35
echo "== 3/3 Dashboard smoke test =="
if command -v node >/dev/null 2>&1; then
  java -cp build/classes salesetl.Main --input data/raw_sales.csv --out out >/dev/null
  python3 tools/build_dashboard_data.py out sql dashboard
  node tests/dashboard_smoke.js
else
  echo "(node not installed - skipping dashboard smoke test)"
fi
echo "ALL TESTS PASSED"
