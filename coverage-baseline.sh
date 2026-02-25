#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
UI_DIR="$ROOT_DIR/ui"

echo "==> Running backend tests"
(
  cd "$BACKEND_DIR"
  mvn -q test
  mvn -q -pl core,collectors,service -am -DskipTests jacoco:report
)

echo "==> Running UI coverage"
(
  cd "$UI_DIR"
  npm run test:coverage --silent
)

backend_module_summary() {
  local csv_file="$1"
  local module_name="$2"
  awk -F, -v module="$module_name" '
    NR > 1 {
      lm += $8
      lc += $9
      mb += $6
      cb += $7
    }
    END {
      line_total = lc + lm
      branch_total = cb + mb
      line_pct = (line_total > 0) ? (lc / line_total) * 100 : 0
      branch_pct = (branch_total > 0) ? (cb / branch_total) * 100 : 0
      printf "%s: line %.2f%%, branch %.2f%%\n", module, line_pct, branch_pct
      printf "%d %d %d %d\n", lc, line_total, cb, branch_total
    }
  ' "$csv_file"
}

echo
echo "Coverage baseline summary"
echo "Backend:"
read -r core_ci core_total core_cb core_btotal < <(backend_module_summary "$BACKEND_DIR/core/target/site/jacoco/jacoco.csv" "core" | tail -n 1)
backend_module_summary "$BACKEND_DIR/core/target/site/jacoco/jacoco.csv" "core" | head -n 1
read -r collectors_ci collectors_total collectors_cb collectors_btotal < <(backend_module_summary "$BACKEND_DIR/collectors/target/site/jacoco/jacoco.csv" "collectors" | tail -n 1)
backend_module_summary "$BACKEND_DIR/collectors/target/site/jacoco/jacoco.csv" "collectors" | head -n 1
read -r service_ci service_total service_cb service_btotal < <(backend_module_summary "$BACKEND_DIR/service/target/site/jacoco/jacoco.csv" "service" | tail -n 1)
backend_module_summary "$BACKEND_DIR/service/target/site/jacoco/jacoco.csv" "service" | head -n 1

overall_ci=$((core_ci + collectors_ci + service_ci))
overall_total=$((core_total + collectors_total + service_total))
overall_cb=$((core_cb + collectors_cb + service_cb))
overall_btotal=$((core_btotal + collectors_btotal + service_btotal))

overall_line_pct=$(awk -v c="$overall_ci" -v t="$overall_total" 'BEGIN { printf "%.2f", (t > 0 ? (c / t) * 100 : 0) }')
overall_branch_pct=$(awk -v c="$overall_cb" -v t="$overall_btotal" 'BEGIN { printf "%.2f", (t > 0 ? (c / t) * 100 : 0) }')

echo "overall backend weighted: line ${overall_line_pct}%, branch ${overall_branch_pct}%"

UI_LCOV="$UI_DIR/coverage/lcov.info"
if [[ ! -f "$UI_LCOV" ]]; then
  echo "UI: coverage file not found at $UI_LCOV"
  exit 1
fi

read -r ui_line_pct ui_branch_pct ui_function_pct < <(
  awk -F: '
    /^LF:/ { lf += $2 }
    /^LH:/ { lh += $2 }
    /^BRF:/ { brf += $2 }
    /^BRH:/ { brh += $2 }
    /^FNF:/ { fnf += $2 }
    /^FNH:/ { fnh += $2 }
    END {
      line_pct = (lf > 0) ? (lh / lf) * 100 : 0
      branch_pct = (brf > 0) ? (brh / brf) * 100 : 0
      function_pct = (fnf > 0) ? (fnh / fnf) * 100 : 0
      printf "%.2f %.2f %.2f\n", line_pct, branch_pct, function_pct
    }
  ' "$UI_LCOV"
)

echo "UI: line ${ui_line_pct}%, branch ${ui_branch_pct}%, function ${ui_function_pct}%"
echo
echo "Reports:"
echo "- backend/core/target/site/jacoco/index.html"
echo "- backend/collectors/target/site/jacoco/index.html"
echo "- backend/service/target/site/jacoco/index.html"
echo "- ui/coverage/index.html"
