param(
    [string]$PackageRoot = "modules/refactorkit-cli/build/package/refactorkit"
)

$ErrorActionPreference = "Stop"
$PackageRoot = (Resolve-Path $PackageRoot).Path
$Launcher = Join-Path $PackageRoot "bin/refactorkit.bat"
$RuntimeJava = Join-Path $PackageRoot "runtime/bin/java.exe"
if (-not (Test-Path $Launcher -PathType Leaf)) { throw "Packaged launcher missing: $Launcher" }
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
    public String find(String key) { return key; }
    public String find(int id) { return String.valueOf(id); }
}
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "Service.java")
    @'
package com.acme;
public class ServiceClient {
    String text(Service service) { return service.find("abc"); }
    String number(Service service) { return service.find(7); }
}
'@ | Set-Content -NoNewline -Encoding utf8 (Join-Path $SourceDir "ServiceClient.java")

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

    $FormatOutput = (& $Launcher format-file src/main/java/com/acme/Service.java --apply --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Managed format smoke failed: $FormatOutput" }
    $Match = [regex]::Match($FormatOutput, 'transaction-[0-9a-f-]+')
    if (-not $Match.Success) { throw "Managed format returned no transaction: $FormatOutput" }
    $TransactionId = $Match.Value
    $RollbackOutput = (& $Launcher patch rollback $TransactionId --root $Fixture) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Managed rollback smoke failed: $RollbackOutput" }
    $RolledBack = (Get-SourceHashes) -join "`n"
    if ($Before -ne $RolledBack) { throw "Packaged rollback did not restore Java sources" }

    Write-Output "Packaged Windows runtime smoke passed: java.compiler present; signed selectors exact; managed format/apply/rollback restored sources."
}
finally {
    Remove-Item -Recurse -Force $Fixture -ErrorAction SilentlyContinue
}
