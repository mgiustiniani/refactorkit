param(
    [string]$PackageRoot = "modules/refactorkit-cli/build/package/refactorkit"
)

$ErrorActionPreference = "Stop"
$PackageRoot = (Resolve-Path $PackageRoot).Path
$Launcher = Join-Path $PackageRoot "bin/refactorkit.bat"
$DaemonLauncher = Join-Path $PackageRoot "bin/refactorkit-daemon.bat"
$RuntimeJava = Join-Path $PackageRoot "runtime/bin/java.exe"
if (-not (Test-Path $Launcher -PathType Leaf)) { throw "Packaged launcher missing: $Launcher" }
if (-not (Test-Path $DaemonLauncher -PathType Leaf)) { throw "Packaged daemon launcher missing: $DaemonLauncher" }
if (-not (Test-Path $RuntimeJava -PathType Leaf)) { throw "Bundled runtime java missing: $RuntimeJava" }

$Modules = & $RuntimeJava --list-modules
if ($LASTEXITCODE -ne 0 -or -not ($Modules -match '^java\.compiler(@|$)')) {
    throw "Bundled runtime is missing java.compiler"
}

$Fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("refactorkit-packaged-smoke-" + [guid]::NewGuid())
$SourceDir = Join-Path $Fixture "src/main/java/com/acme"
New-Item -ItemType Directory -Force -Path $SourceDir | Out-Null
try {
    @'
package com.acme;
public class Service {
    String find(String key) { return key; }
    String find(int id) { return String.valueOf(id); }
    int size(CharSequence text) { return text.length(); }
}
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "Service.java")
    @'
package com.acme;
public class ServiceClient {
    String text(Service service) { return service.find("abc"); }
    String number(Service service) { return service.find(7); }
}
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "ServiceClient.java")
    @'
package com.acme;
public interface Lookup { String find(String key, boolean unused); }
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "Lookup.java")
    @'
package com.acme;
public class DefaultLookup implements Lookup {
    @Override public String find(String value, boolean ignored) { return value; }
}
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "DefaultLookup.java")
    @'
package com.acme;
class HierarchyCaller {
    String run(Lookup lookup) { return lookup.find("x", true); }
}
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "HierarchyCaller.java")
    @'
// class FakeNativeBinding {}
export interface NativeService { run(): void }
export class RealNativeBinding { run(): void {} }
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $Fixture "structural.ts")

    $Outline = (& $Launcher outline (Join-Path $Fixture "structural.ts") --language typescript) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $Outline -notmatch 'INTERFACE\s+NativeService' -or
        $Outline -notmatch 'CLASS\s+RealNativeBinding' -or $Outline -match 'FakeNativeBinding') {
        throw "Packaged native Tree-sitter outline failed: $Outline"
    }

    function Get-SourceHashes {
        Get-ChildItem -Path $Fixture -Filter *.java -Recurse |
            Sort-Object FullName |
            ForEach-Object { "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.FullName }
    }

    $Before = (Get-SourceHashes) -join "`n"
    $Symbol = 'com.acme.Service#find(java.lang.String)'
    $Definition = (& $Launcher definition --symbol $Symbol $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Definition smoke failed: $Definition" }
    $References = (& $Launcher references --symbol $Symbol $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "References smoke failed: $References" }
    $After = (Get-SourceHashes) -join "`n"

    if ($Definition -notmatch 'src[/\\]main[/\\]java[/\\]com[/\\]acme[/\\]Service\.java:3') {
        throw "Unexpected packaged definition output: $Definition"
    }
    if ($References -notmatch 'src[/\\]main[/\\]java[/\\]com[/\\]acme[/\\]ServiceClient\.java:3') {
        throw "Unexpected packaged references output: $References"
    }
    if ($References -match 'src[/\\]main[/\\]java[/\\]com[/\\]acme[/\\]ServiceClient\.java:4') {
        throw "Signed-selector references included the int overload: $References"
    }
    if ($Before -ne $After) { throw "Packaged read-only smoke modified Java sources" }

    $SignaturePreview = (& $Launcher change-signature --symbol $Symbol --old-name key --new-name lookupKey $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $SignaturePreview -notmatch 'Rename JDT-proven parameter' -or
        $Before -ne ((Get-SourceHashes) -join "`n")) {
        throw "Packaged JDT parameter preview failed or wrote sources: $SignaturePreview"
    }
    $SignatureOutput = (& $Launcher change-signature --symbol $Symbol --old-name key --new-name lookupKey --apply $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Packaged JDT parameter apply failed: $SignatureOutput" }
    $SignatureMatch = [regex]::Match($SignatureOutput, 'transaction-[0-9a-f-]+')
    $ServiceContent = Get-Content -Raw (Join-Path $SourceDir "Service.java")
    if (-not $SignatureMatch.Success -or
        $ServiceContent -notmatch 'find\(String lookupKey\) \{ return lookupKey; \}' -or
        $ServiceContent -notmatch 'find\(int id\) \{ return String\.valueOf\(id\); \}') {
        throw "Packaged JDT parameter apply changed the wrong overload: $SignatureOutput"
    }
    $SignatureRollback = (& $Launcher patch rollback $SignatureMatch.Value --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $Before -ne ((Get-SourceHashes) -join "`n")) {
        throw "Packaged JDT parameter rollback failed: $SignatureRollback"
    }

    $TypeOutput = (& $Launcher change-signature --operation change-parameter-type --symbol 'com.acme.Service#size(java.lang.CharSequence)' --name text --type String --apply $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Packaged JDT parameter type change failed: $TypeOutput" }
    $TypeMatch = [regex]::Match($TypeOutput, 'transaction-[0-9a-f-]+')
    $TypeContent = Get-Content -Raw (Join-Path $SourceDir "Service.java")
    if (-not $TypeMatch.Success -or $TypeContent -notmatch 'size\(String text\)') {
        throw "Packaged JDT parameter type post-image mismatch: $TypeOutput"
    }
    $TypeRollback = (& $Launcher patch rollback $TypeMatch.Value --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $Before -ne ((Get-SourceHashes) -join "`n")) {
        throw "Packaged JDT parameter type rollback failed: $TypeRollback"
    }

    $HierarchyOutput = (& $Launcher change-signature --operation add-parameter --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --type int --name limit --default 10 --include-hierarchy --accept-external-consumer-risk --apply $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Packaged JDT hierarchy change failed: $HierarchyOutput" }
    $HierarchyMatch = [regex]::Match($HierarchyOutput, 'transaction-[0-9a-f-]+')
    $LookupContent = Get-Content -Raw (Join-Path $SourceDir "Lookup.java")
    $ImplementationContent = Get-Content -Raw (Join-Path $SourceDir "DefaultLookup.java")
    $HierarchyCallerContent = Get-Content -Raw (Join-Path $SourceDir "HierarchyCaller.java")
    if (-not $HierarchyMatch.Success -or $LookupContent -notmatch 'find\(String key, boolean unused, int limit\)'  -or
        $ImplementationContent -notmatch 'find\(String value, boolean ignored, int limit\)'  -or
        $HierarchyCallerContent -notmatch 'find\("x", true, 10\)' ) {
        throw "Packaged JDT hierarchy post-image mismatch: $HierarchyOutput"
    }
    $HierarchyRollback = (& $Launcher patch rollback $HierarchyMatch.Value --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $Before -ne ((Get-SourceHashes) -join "`n")) {
        throw "Packaged JDT hierarchy rollback failed: $HierarchyRollback"
    }

    $HierarchyRemove = (& $Launcher change-signature --operation remove-parameter --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --name unused --include-hierarchy --accept-external-consumer-risk --apply $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Packaged JDT hierarchy remove failed: $HierarchyRemove" }
    $HierarchyRemoveMatch = [regex]::Match($HierarchyRemove, 'transaction-[0-9a-f-]+')
    $HierarchyCallerContent = Get-Content -Raw (Join-Path $SourceDir "HierarchyCaller.java")
    if (-not $HierarchyRemoveMatch.Success -or $HierarchyCallerContent -notmatch 'find\("x"\)') {
        throw "Packaged JDT hierarchy remove post-image mismatch: $HierarchyRemove"
    }
    $HierarchyRemoveRollback = (& $Launcher patch rollback $HierarchyRemoveMatch.Value --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $Before -ne ((Get-SourceHashes) -join "`n")) {
        throw "Packaged JDT hierarchy remove rollback failed: $HierarchyRemoveRollback"
    }

    $HierarchyReorder = (& $Launcher change-signature --operation reorder-parameters --symbol 'com.acme.Lookup#find(java.lang.String,boolean)' --order unused,key --include-hierarchy --accept-external-consumer-risk --apply $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Packaged JDT hierarchy reorder failed: $HierarchyReorder" }
    $HierarchyReorderMatch = [regex]::Match($HierarchyReorder, 'transaction-[0-9a-f-]+')
    $HierarchyCallerContent = Get-Content -Raw (Join-Path $SourceDir "HierarchyCaller.java")
    $ImplementationContent = Get-Content -Raw (Join-Path $SourceDir "DefaultLookup.java")
    if (-not $HierarchyReorderMatch.Success -or $HierarchyCallerContent -notmatch 'find\(true, "x"\)' -or $ImplementationContent -notmatch 'find\(boolean ignored, String value\)') {
        throw "Packaged JDT hierarchy reorder post-image mismatch: $HierarchyReorder"
    }
    $HierarchyReorderRollback = (& $Launcher patch rollback $HierarchyReorderMatch.Value --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $Before -ne ((Get-SourceHashes) -join "`n")) {
        throw "Packaged JDT hierarchy reorder rollback failed: $HierarchyReorderRollback"
    }

    $FormatOutput = (& $Launcher format-file src/main/java/com/acme/Service.java --apply --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Managed format smoke failed: $FormatOutput" }
    $Match = [regex]::Match($FormatOutput, 'transaction-[0-9a-f-]+')
    if (-not $Match.Success) { throw "Managed format returned no transaction: $FormatOutput" }
    $TransactionId = $Match.Value
    $RollbackOutput = (& $Launcher patch rollback $TransactionId --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Managed rollback smoke failed: $RollbackOutput" }
    $RolledBack = (Get-SourceHashes) -join "`n"
    if ($Before -ne $RolledBack) { throw "Packaged rollback did not restore Java sources" }

    & python scripts/test-smoke-packaged-daemon-timeout.py
    if ($LASTEXITCODE -ne 0) { throw "Packaged daemon timeout self-test failed" }
    & python scripts/smoke-packaged-daemon.py $DaemonLauncher
    if ($LASTEXITCODE -ne 0) { throw "Packaged daemon smoke failed" }
    & python scripts/smoke-packaged-java-change-signature.py $PackageRoot
    if ($LASTEXITCODE -ne 0) { throw "Packaged Java change-signature transport smoke failed" }
    Write-Output "Packaged Windows runtime smoke passed: java.compiler present; signed selectors and JDT parameter/hierarchy changes exact; managed apply/rollback restored sources; daemon lifecycle verified."
}
finally {
    Remove-Item -Recurse -Force $Fixture -ErrorAction SilentlyContinue
}
