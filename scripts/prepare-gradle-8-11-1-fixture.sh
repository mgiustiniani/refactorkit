#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}"

fixture=$(mktemp -d "$RUNNER_TEMP/refactorkit-gradle-8.11.1.XXXXXX")
archive="$fixture/gradle-8.11.1-bin.zip"
url='https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
# Published at https://gradle.org/release-checksums/.
checksum='f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'

curl --fail --silent --show-error --location --proto '=https' --retry 2 --max-time 180 --output "$archive" "$url"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check --strict
unzip -q "$archive" -d "$fixture"
launcher="$fixture/gradle-8.11.1/bin/gradle"
test -x "$launcher"
printf 'REFACTORKIT_GRADLE_8_11_1_EXECUTABLE=%s\n' "$launcher" >> "$GITHUB_ENV"
