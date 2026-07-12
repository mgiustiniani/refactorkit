#!/usr/bin/env bash
set -euo pipefail

RK="${1:-modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit}"
FILES="${REFACTORKIT_BENCHMARK_FILES:-1000}"
ROOT="$(mktemp -d "${TMPDIR:-/tmp}/refactorkit-benchmark.XXXXXX")"
trap 'rm -rf "$ROOT"' EXIT

python3 - "$ROOT" "$FILES" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1]) / "src/main/java/benchmark"
count = int(sys.argv[2])
root.mkdir(parents=True)
for i in range(count):
    (root / f"Type{i}.java").write_text(
        "package benchmark;\n"
        f"public class Type{i} {{ public int value() {{ return {i}; }} }}\n"
    )
PY

run_budget() {
  local name="$1" ceiling="$2"
  shift 2
  local start end elapsed
  start="$(date +%s)"
  timeout "$ceiling" "$@" >/dev/null
  end="$(date +%s)"
  elapsed="$((end - start))"
  printf '%-14s files=%s elapsed=%ss ceiling=%ss\n' "$name" "$FILES" "$elapsed" "$ceiling"
}

run_budget scan 30 "$RK" scan "$ROOT"
run_budget symbols 60 "$RK" symbols "$ROOT"
run_budget diagnostics 120 "$RK" diagnostics "$ROOT"
