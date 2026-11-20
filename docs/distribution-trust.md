# Distribution trust

Status: P3 baseline for `0.5.x`.

## Current signing status

RefactorKit ZIP runtimes are **not currently platform-code-signed**:

- macOS assets have no Developer ID signature and are not notarized. Gatekeeper
  may report an unidentified developer when the downloaded archive has quarantine
  metadata.
- Windows launchers/runtime binaries have no Authenticode signature. SmartScreen
  may report an unknown publisher or reputation warning.
- Linux assets have no detached GPG/Sigstore release signature beyond GitHub
  artifact attestations.

Release notes must state these facts for every release until the corresponding
signing pipeline is enabled. A checksum or GitHub attestation proves artifact
identity/provenance; it does not substitute for Developer ID, notarization,
Authenticode, or reputation.

Signing credentials must never be stored in the repository. Developer ID and
Authenticode implementation requires separately provisioned CI secrets/HSM or
keyless signing design, certificate rotation/revocation procedures, timestamping,
and native verification acceptance.

## Archive verification

`scripts/verify-runtime-archive.py` verifies an archive before trusting
extraction:

- SHA-256 file and archive basename;
- bounded entry count and expanded size;
- traversal, absolute path, symlink, duplicate, and case-fold collision refusal;
- reproducible timestamps;
- required archive layout and application JARs;
- Unix executable permission metadata;
- required `jlink` modules including `java.compiler`;
- ELF/PE/Mach-O architecture against the declared platform;
- extracted packaged launcher with `JAVA_HOME` removed when running natively.

The CI native matrix runs the verifier on each host. The release publish job
downloads the independently uploaded per-platform inputs and runs the verifier
again with `--no-execute` before publication. Malicious archive/checksum fixtures
cover traversal, case collision, digest mismatch, and architecture parsing.

Example:

```bash
python3 scripts/verify-runtime-archive.py \
  refactorkit-runtime-0.6.2-linux-x86_64.zip \
  refactorkit-runtime-0.6.2-linux-x86_64.zip.sha256 \
  --platform linux-x86_64
```

## SBOM and GitHub attestations

Release jobs generate a platform-specific SPDX JSON SBOM, GitHub build-provenance
attestation, and GitHub SBOM attestation for each ZIP. The publish job verifies
checksums and archive structure after downloading job artifacts. Consumers should
also verify the release checksum and, where available, GitHub attestations against
the repository and release tag using GitHub's documented attestation tooling.

The SBOM describes packaged content but does not establish code-signing identity.
Attestations are not copied between platform archives: each native asset has its
own subject digest.

## Independent Linux v0.8.0 release verification

The [0.8.0 release plan](releases/v0.8.0-plan.md#testing-and-release-gates) calls
for independent verification of the draft assets and the public download. The
[ARC42 acceptance entry](arc42/appendix-requirements.adoc#c17-v080-downloaded-asset-review-2026-11-19)
defines what the verifier checks. Use a clean Linux x86-64 checkout of the reviewed
tag and the pinned Clang qualification directory. The checkout supplies the
reviewed script, fixture and expected revision. [Clang preparation script](../scripts/prepare-clang-qualification-toolchain.sh)

Run this as a Bash script first with `phase=draft`, using GitHub CLI access to
read the draft. After publication, run it again with `phase=public` to retrieve
all four assets from their public release URLs without authentication. Each run
uses a fresh directory. GitHub CLI verifies the three attestations in both
phases. [Release download manual](https://cli.github.com/manual/gh_release_download) · [Attestation verification manual](https://cli.github.com/manual/gh_attestation_verify)

```bash
set -euo pipefail
phase=draft
repo=mgiustiniani/refactorkit
tag=v0.8.0
revision=$(git rev-parse --verify "refs/tags/$tag^{commit}")
test "$(git rev-parse HEAD)" = "$revision"
test -z "$(git status --porcelain)"
gh_path=$(command -v gh)
toolchain=${REFACTORKIT_C_QUALIFICATION_TOOLCHAIN:?Set the verified Clang toolchain directory}
work=$(mktemp -d)
mkdir "$work/assets"
asset="refactorkit-runtime-${tag#v}-linux-x86_64"
sbom=refactorkit-runtime-linux-x86_64.spdx.json
case "$phase" in
  draft)
    test "$(gh release view "$tag" --repo "$repo" --json isDraft --jq .isDraft)" = true
    gh release download "$tag" --repo "$repo" --dir "$work/assets" \
      --pattern "$asset.zip" --pattern "$asset.zip.sha256" \
      --pattern "$asset.release.json" --pattern "$sbom"
    ;;
  public)
    for name in "$asset.zip" "$asset.zip.sha256" "$asset.release.json" "$sbom"; do
      curl --disable --fail --location --output "$work/assets/$name" \
        "https://github.com/$repo/releases/download/$tag/$name"
    done
    ;;
  *) exit 2 ;;
esac
python3 scripts/verify-v080-downloaded-release.py \
  --asset-dir "$work/assets" --gh "$gh_path" --repo "$repo" \
  --tag "$tag" --revision "$revision" --receipt "$work/$phase-verification.json"
unzip -q "$work/assets/$asset.zip" -d "$work/runtime"
env -u JAVA_HOME "$work/runtime/refactorkit/bin/refactorkit" test-golden c-relocate \
  --golden-dir "$PWD/testdata/golden" \
  --clang "$toolchain/usr/bin/clang-22" --clangd "$toolchain/usr/bin/clangd" \
  --clang-format "$toolchain/usr/bin/clang-format" | tee "$work/$phase-c-relocate.log"
printf 'Review files are in %s\n' "$work"
```

Retain both verification receipts and their operation logs. The relocation
fixture checks preview, exact managed apply and rollback on the extracted
runtime. The draft and public receipts must list the same repository, tag,
source commit and four asset digests. Record the two download results in the
release completion register alongside the independent distribution review.

## Platform warning guidance

Do not instruct users to bypass platform warnings blindly. If an unsigned release
must be used, first verify its SHA-256 and provenance. Any quarantine removal or
SmartScreen override is a user security decision and must be limited to the exact
verified archive. Signed/notarized installers remain independent future work;
ZIP publication and verification do not depend on installer availability.
