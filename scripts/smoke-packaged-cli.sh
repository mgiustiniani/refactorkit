#!/usr/bin/env bash
set -euo pipefail

package_root="${1:-modules/refactorkit-cli/build/package/refactorkit}"
package_root="$(cd "$package_root" && pwd)"
launcher="$package_root/bin/refactorkit"
daemon_launcher="$package_root/bin/refactorkit-daemon"
runtime_java="$package_root/runtime/bin/java"

if [[ ! -x "$launcher" ]]; then
  echo "Packaged launcher is not executable: $launcher" >&2
  exit 1
fi
if [[ ! -x "$daemon_launcher" ]]; then
  echo "Packaged daemon launcher is not executable: $daemon_launcher" >&2
  exit 1
fi
if [[ ! -x "$runtime_java" ]]; then
  echo "Bundled runtime java is not executable: $runtime_java" >&2
  exit 1
fi

modules="$($runtime_java --list-modules)"
if ! grep -Eq '^java\.compiler(@|$)' <<<"$modules"; then
  echo "Bundled runtime is missing java.compiler" >&2
  exit 1
fi

fixture="$(mktemp -d "${TMPDIR:-/tmp}/refactorkit-packaged-smoke.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/src/main/java/com/acme"
cat >"$fixture/src/main/java/com/acme/Service.java" <<'JAVA'
package com.acme;
public class Service {
    public String find(String key) { return key; }
    public String find(int id) { return String.valueOf(id); }
}
JAVA
cat >"$fixture/src/main/java/com/acme/ServiceClient.java" <<'JAVA'
package com.acme;
public class ServiceClient {
    String text(Service service) { return service.find("abc"); }
    String number(Service service) { return service.find(7); }
}
JAVA

source_hashes() {
  find "$fixture" -type f -name '*.java' -print0 \
    | sort -z \
    | xargs -0 sha256sum
}

before="$(source_hashes)"
symbol='com.acme.Service#find(java.lang.String)'
definition="$(env -u JAVA_HOME "$launcher" definition --symbol "$symbol" "$fixture")"
references="$(env -u JAVA_HOME "$launcher" references --symbol "$symbol" "$fixture")"
after="$(source_hashes)"

if ! grep -Fq 'src/main/java/com/acme/Service.java:3' <<<"$definition"; then
  echo "Unexpected packaged definition output:" >&2
  echo "$definition" >&2
  exit 1
fi
if ! grep -Fq 'src/main/java/com/acme/ServiceClient.java:3' <<<"$references"; then
  echo "Unexpected packaged references output:" >&2
  echo "$references" >&2
  exit 1
fi
if grep -Fq 'src/main/java/com/acme/ServiceClient.java:4' <<<"$references"; then
  echo "Signed-selector references included the int overload:" >&2
  echo "$references" >&2
  exit 1
fi
if [[ "$before" != "$after" ]]; then
  echo "Packaged read-only smoke modified Java sources" >&2
  diff -u <(printf '%s\n' "$before") <(printf '%s\n' "$after") || true
  exit 1
fi

format_output="$(env -u JAVA_HOME "$launcher" format-file src/main/java/com/acme/Service.java --apply --root "$fixture")"
transaction_id="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$format_output" | tail -1)"
if [[ -z "$transaction_id" ]] || ! grep -Fq 'public String find(String key) {' "$fixture/src/main/java/com/acme/Service.java"; then
  echo "Packaged managed format smoke failed:" >&2
  echo "$format_output" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$transaction_id" --root "$fixture" >/dev/null
rolled_back="$(source_hashes)"
if [[ "$before" != "$rolled_back" ]]; then
  echo "Packaged rollback did not restore Java sources" >&2
  diff -u <(printf '%s\n' "$before") <(printf '%s\n' "$rolled_back") || true
  exit 1
fi

python3 scripts/smoke-packaged-daemon.py "$daemon_launcher"
printf '%s\n' "Packaged runtime smoke passed: java.compiler present; signed selectors exact; managed format/apply/rollback restored sources; daemon lifecycle verified."
