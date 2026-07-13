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
  refactorkit-runtime-0.6.1-linux-x86_64.zip \
  refactorkit-runtime-0.6.1-linux-x86_64.zip.sha256 \
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

## Platform warning guidance

Do not instruct users to bypass platform warnings blindly. If an unsigned release
must be used, first verify its SHA-256 and provenance. Any quarantine removal or
SmartScreen override is a user security decision and must be limited to the exact
verified archive. Signed/notarized installers remain independent future work;
ZIP publication and verification do not depend on installer availability.
