#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="${1:?packaged RefactorKit directory is required}"
CLI="$PACKAGE_DIR/bin/refactorkit"
ROOT="$(mktemp -d "${TMPDIR:-/tmp}/refactorkit-packaged-recipe.XXXXXX")"
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/src/main/java/com/old"
printf 'package com.old;\npublic class Example {}\n' > "$ROOT/src/main/java/com/old/Example.java"
cat > "$ROOT/recipe.yml" <<'YAML'
id: packaged.recipe.smoke
name: Packaged recipe smoke
language: java
parameters:
  oldPackage: string
  newPackage: string
steps:
  - type: movePackage
    from: "{{ oldPackage }}"
    to: "{{ newPackage }}"
  - type: summarizePatch
YAML

output="$($CLI recipe run "$ROOT/recipe.yml" \
  --root "$ROOT" \
  --param.oldPackage com.old \
  --param.newPackage com.newpkg)"

grep -q "Status: PREVIEW" <<<"$output"
grep -q "com/newpkg/Example.java" <<<"$output"
test -f "$ROOT/src/main/java/com/old/Example.java"
test ! -e "$ROOT/src/main/java/com/newpkg/Example.java"

echo "Packaged YAML recipe smoke passed."
