#!/usr/bin/env bash
set -euo pipefail

scope=$(<modules/refactorkit-cli/src/test/resources/cucumber-filter-tags.txt)
partition=${1:?Specify the CI regression partition}
case "$partition" in
  remainder)
    selection="not (@ci-c-rename or @ci-c-search)"
    tasks=(build)
    ;;
  ci-c-rename|ci-c-search)
    selection="@$partition"
    tasks=(:modules:refactorkit-cli:test --tests org.refactorkit.cli.previewcommand.JavaPreviewCommandSurfaceCucumberTest)
    ;;
  *)
    printf 'Unknown CI regression partition: %s\n' "$partition" >&2
    exit 2
    ;;
esac
exec ./gradlew --no-daemon --parallel --max-workers=4 "-Drefactorkit.cli.cucumber.filter.tags=($scope) and $selection" "${tasks[@]}"
