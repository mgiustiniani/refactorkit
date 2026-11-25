#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/refactorkit-maven-add-module.XXXXXX")"
cp -R "$root/modules/refactorkit-cli/src/test/resources/maven-add-module-fixture" "$work/project"
printf '<settings/>\n' > "$work/settings.xml"

# Provision only the plugins used by the authored fixture. The Story runs offline
# against a disposable copy of this repository after applying the refactoring.
MAVEN_OPTS="-Duser.home=$work" mvn -B -ntp \
  -s "$work/settings.xml" -gs "$work/settings.xml" \
  "-Dmaven.repo.local=$work/repository" -f "$work/project/pom.xml" verify

printf 'REFACTORKIT_MAVEN_ADD_MODULE_REPOSITORY=%s\n' "$work/repository" >> "${GITHUB_ENV:-/dev/stdout}"
