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
    String find(String key) { return key; }
    String find(int id) { return String.valueOf(id); }
    int size(CharSequence text) { return text.length(); }
}
JAVA
cat >"$fixture/src/main/java/com/acme/ServiceClient.java" <<'JAVA'
package com.acme;
public class ServiceClient {
    String text(Service service) { return service.find("abc"); }
    String number(Service service) { return service.find(7); }
}
JAVA
cat >"$fixture/src/main/java/com/acme/Lookup.java" <<'JAVA'
package com.acme;
public interface Lookup { String find(String key, boolean unused); }
JAVA
cat >"$fixture/src/main/java/com/acme/DefaultLookup.java" <<'JAVA'
package com.acme;
public class DefaultLookup implements Lookup {
    @Override public String find(String value, boolean ignored) { return value.toString(); }
}
JAVA
cat >"$fixture/src/main/java/com/acme/HierarchyCaller.java" <<'JAVA'
package com.acme;
class HierarchyCaller {
    String run(Lookup lookup) { return lookup.find("x", true); }
}
JAVA
cat >"$fixture/structural.ts" <<'TS'
// class FakeNativeBinding {}
export interface NativeService { run(): void }
export class RealNativeBinding { run(): void {} }
TS

outline="$(env -u JAVA_HOME "$launcher" outline "$fixture/structural.ts" --language typescript)"
if ! grep -Fq 'INTERFACE    NativeService' <<<"$outline" ||
   ! grep -Fq 'CLASS        RealNativeBinding' <<<"$outline" ||
   grep -Fq 'FakeNativeBinding' <<<"$outline"; then
  echo "Packaged native Tree-sitter outline failed:" >&2
  echo "$outline" >&2
  exit 1
fi

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

signature_preview="$(env -u JAVA_HOME "$launcher" change-signature --symbol "$symbol" --old-name key --new-name lookupKey "$fixture")"
if ! grep -Fq 'Rename JDT-proven parameter' <<<"$signature_preview" || [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT parameter preview failed or wrote sources:" >&2
  echo "$signature_preview" >&2
  exit 1
fi
signature_output="$(env -u JAVA_HOME "$launcher" change-signature --symbol "$symbol" --old-name key --new-name lookupKey --apply "$fixture")"
signature_transaction="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$signature_output" | tail -1)"
if [[ -z "$signature_transaction" ]] ||
   ! grep -Fq 'find(String lookupKey) { return lookupKey; }' "$fixture/src/main/java/com/acme/Service.java" ||
   ! grep -Fq 'find(int id) { return String.valueOf(id); }' "$fixture/src/main/java/com/acme/Service.java"; then
  echo "Packaged JDT parameter apply failed:" >&2
  echo "$signature_output" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$signature_transaction" --root "$fixture" >/dev/null
if [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT parameter rollback did not restore Java sources" >&2
  exit 1
fi

type_output="$(env -u JAVA_HOME "$launcher" change-signature --operation change-parameter-type --symbol 'com.acme.Service#size(java.lang.CharSequence)' --name text --type String --apply "$fixture")"
type_transaction="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$type_output" | tail -1)"
if [[ -z "$type_transaction" ]] || ! grep -Fq 'size(String text)' "$fixture/src/main/java/com/acme/Service.java"; then
  echo "Packaged JDT parameter type change failed:" >&2
  echo "$type_output" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$type_transaction" --root "$fixture" >/dev/null
if [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT parameter type rollback did not restore Java sources" >&2
  exit 1
fi

hierarchy_output="$(env -u JAVA_HOME "$launcher" change-signature --operation add-parameter --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --type int --name limit --default 10 --include-hierarchy --accept-external-consumer-risk --apply "$fixture")"
hierarchy_transaction="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$hierarchy_output" | tail -1)"
if [[ -z "$hierarchy_transaction" ]] ||
   ! grep -Fq 'find(String key, boolean unused, int limit)' "$fixture/src/main/java/com/acme/Lookup.java" ||
   ! grep -Fq 'find(String value, boolean ignored, int limit)' "$fixture/src/main/java/com/acme/DefaultLookup.java" ||
   ! grep -Fq 'find("x", true, 10)' "$fixture/src/main/java/com/acme/HierarchyCaller.java"; then
  echo "Packaged JDT hierarchy change failed:" >&2
  echo "$hierarchy_output" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$hierarchy_transaction" --root "$fixture" >/dev/null
if [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT hierarchy rollback did not restore Java sources" >&2
  exit 1
fi

hierarchy_remove="$(env -u JAVA_HOME "$launcher" change-signature --operation remove-parameter --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --name unused --include-hierarchy --accept-external-consumer-risk --apply "$fixture")"
hierarchy_remove_transaction="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$hierarchy_remove" | tail -1)"
if [[ -z "$hierarchy_remove_transaction" ]] || ! grep -Fq 'find("x")' "$fixture/src/main/java/com/acme/HierarchyCaller.java"; then
  echo "Packaged JDT hierarchy remove failed: $hierarchy_remove" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$hierarchy_remove_transaction" --root "$fixture" >/dev/null
if [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT hierarchy remove rollback did not restore Java sources" >&2
  exit 1
fi

hierarchy_reorder="$(env -u JAVA_HOME "$launcher" change-signature --operation reorder-parameters --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --order unused,key --include-hierarchy --accept-external-consumer-risk --apply "$fixture")"
hierarchy_reorder_transaction="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$hierarchy_reorder" | tail -1)"
if [[ -z "$hierarchy_reorder_transaction" ]] || ! grep -Fq 'find(true, "x")' "$fixture/src/main/java/com/acme/HierarchyCaller.java" || ! grep -Fq 'find(boolean ignored, String value)' "$fixture/src/main/java/com/acme/DefaultLookup.java"; then
  echo "Packaged JDT hierarchy reorder failed: $hierarchy_reorder" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$hierarchy_reorder_transaction" --root "$fixture" >/dev/null
if [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT hierarchy reorder rollback did not restore Java sources" >&2
  exit 1
fi

hierarchy_type="$(env -u JAVA_HOME "$launcher" change-signature --operation change-parameter-type --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --name key --type CharSequence --include-hierarchy --accept-external-consumer-risk --apply "$fixture")"
hierarchy_type_transaction="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$hierarchy_type" | tail -1)"
if [[ -z "$hierarchy_type_transaction" ]] || ! grep -Fq 'find(CharSequence key, boolean unused)' "$fixture/src/main/java/com/acme/Lookup.java" || ! grep -Fq 'find(CharSequence value, boolean ignored)' "$fixture/src/main/java/com/acme/DefaultLookup.java"; then
  echo "Packaged JDT hierarchy type change failed: $hierarchy_type" >&2
  exit 1
fi
env -u JAVA_HOME "$launcher" patch rollback "$hierarchy_type_transaction" --root "$fixture" >/dev/null
if [[ "$before" != "$(source_hashes)" ]]; then
  echo "Packaged JDT hierarchy type rollback did not restore Java sources" >&2
  exit 1
fi

format_output="$(env -u JAVA_HOME "$launcher" format-file src/main/java/com/acme/Service.java --apply --root "$fixture")"
transaction_id="$(grep -Eo 'transaction-[0-9a-f-]+' <<<"$format_output" | tail -1)"
if [[ -z "$transaction_id" ]] || ! grep -Fq 'String find(String key) {' "$fixture/src/main/java/com/acme/Service.java"; then
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

python3 scripts/test-smoke-packaged-daemon-timeout.py
python3 scripts/smoke-packaged-daemon.py "$daemon_launcher"
python3 scripts/smoke-packaged-java-change-signature.py "$package_root"
printf '%s\n' "Packaged runtime smoke passed: java.compiler present; signed selectors and JDT parameter/hierarchy changes exact; managed apply/rollback restored sources; daemon lifecycle verified."
