#!/usr/bin/env python3
"""Fail-closed reconciliation for the v0.7.0 Kotlin support projection."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = ROOT / "docs/releases/v0.7.0-kotlin-support.json"
KOTLIN_BUILD = ROOT / "modules/refactorkit-kotlin/build.gradle.kts"
TOOLCHAIN = ROOT / "modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinToolchainDiscovery.kt"
ADAPTER = ROOT / "modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinLanguageAdapter.kt"
PACKAGED_SMOKE = ROOT / "scripts/smoke-packaged-kotlin.py"
KOTLIN_SYMBOL_SCHEMA = ROOT / "docs/api-0.2-kotlin-symbols-schema.json"

EXPECTED_SHAPES = {
    "kotlin-jvm": ("operation-specific-candidate", "proposal-only"),
    "kotlin-multiplatform": ("refused", "none"),
    "android": ("refused", "none"),
    "compiler-plugins-kapt-ksp": ("refused", "none"),
    "generated-code-mutation": ("refused", "none"),
    "expect-actual": ("refused", "none"),
    "scripts-kts": ("refused", "none"),
}
EXPECTED_LICENSES = {
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21": ("Apache-2.0", "caller-supplied-toolchain"),
    "org.jetbrains.kotlin:kotlin-stdlib:2.0.21": ("Apache-2.0", "packaged-and-caller-supplied"),
    "org.jetbrains.kotlin:kotlin-script-runtime:2.0.21": ("Apache-2.0", "caller-supplied-toolchain"),
    "org.jetbrains.kotlin:kotlin-reflect:1.6.10": ("Apache-2.0", "caller-supplied-toolchain"),
    "org.jetbrains.kotlin:kotlin-daemon-embeddable:2.0.21": ("Apache-2.0", "caller-supplied-toolchain"),
    "org.jetbrains.intellij.deps:trove4j:1.0.20200330": ("LGPL-2.1-only", "caller-supplied-toolchain"),
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4": ("Apache-2.0", "caller-supplied-toolchain"),
    "org.jetbrains:annotations:13.0": ("Apache-2.0", "caller-supplied-toolchain"),
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3": ("Apache-2.0", "packaged-runtime"),
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3": ("Apache-2.0", "packaged-runtime"),
}
REQUIRED_NON_CLAIMS = {
    "No general Kotlin managed-support claim.",
    "No Kotlin Multiplatform or Android semantic authority.",
    "No compiler-plugin, kapt, KSP, expect/actual, generated-code mutation, or script authority.",
    "No Gradle execution-derived effective model.",
    "No final release SBOM, signing, publication, or downloaded-asset verification claim.",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fail(failures: list[str], message: str) -> None:
    failures.append(message)


def main() -> int:
    failures: list[str] = []
    try:
        payload = json.loads(SUPPORT.read_text(encoding="utf-8"))
    except Exception as problem:
        payload = {}
        fail(failures, f"support projection is unreadable: {problem}")

    if payload.get("schemaVersion") != 1:
        fail(failures, "schemaVersion must be 1")
    if payload.get("release") != "0.7.0-SNAPSHOT" or payload.get("status") != "active-qualification":
        fail(failures, "release/status identity is invalid")

    toolchains = payload.get("toolchains")
    if not isinstance(toolchains, list) or len(toolchains) != 1:
        fail(failures, "exactly one bounded Kotlin toolchain row is required")
    else:
        toolchain = toolchains[0]
        expected = {
            "provider": "kotlin-compiler-explicit-v1",
            "backend": "kotlin-compiler-embeddable-k2",
            "kotlinVersion": "2.0.21",
            "jdkMajor": 21,
            "state": "candidate-bounded",
        }
        for key, value in expected.items():
            if toolchain.get(key) != value:
                fail(failures, f"toolchain {key} must be {value!r}")
        if not toolchain.get("boundary"):
            fail(failures, "toolchain boundary must be explicit")

    models = payload.get("buildModels")
    model_states = {
        row.get("provider"): row.get("state")
        for row in models if isinstance(row, dict)
    } if isinstance(models, list) else {}
    if model_states != {
        "maven-effective-v1": "candidate-bounded",
        "gradle-declarative-v1": "declarative-partial",
    }:
        fail(failures, "Maven/Gradle support rows are missing or widened")
    if not isinstance(models, list) or any(
        not row.get("boundary") or not row.get("versionBoundary")
        for row in models if isinstance(row, dict)
    ):
        fail(failures, "every build-model row requires explicit behavior and version boundaries")

    shapes = payload.get("capabilityBoundaries")
    shape_states = {
        row.get("shape"): (row.get("state"), row.get("mutationAuthority"))
        for row in shapes if isinstance(row, dict)
    } if isinstance(shapes, list) else {}
    if shape_states != EXPECTED_SHAPES:
        fail(failures, "Kotlin capability/refusal matrix is incomplete or widened")

    license_boundary = payload.get("licenseBoundary")
    licenses = {
        row.get("component"): (row.get("spdxLicense"), row.get("distribution"))
        for row in license_boundary.get("components", []) if isinstance(row, dict)
    } if isinstance(license_boundary, dict) else {}
    if licenses != EXPECTED_LICENSES:
        fail(failures, "dependency license boundary is incomplete or changed")
    if not isinstance(license_boundary, dict) or license_boundary.get("state") != "published-dependency-boundary" or not license_boundary.get("boundary"):
        fail(failures, "license boundary state/text is invalid")

    sbom = payload.get("sbomBoundary")
    if not isinstance(sbom, dict) or sbom.get("state") != "configured-unpublished" or sbom.get("format") != "SPDX-JSON" or not sbom.get("boundary"):
        fail(failures, "SBOM boundary must remain configured-unpublished SPDX-JSON")
    if set(payload.get("nonClaims", [])) != REQUIRED_NON_CLAIMS:
        fail(failures, "required Kotlin/release non-claims are incomplete")

    try:
        build_text = KOTLIN_BUILD.read_text(encoding="utf-8")
        toolchain_text = TOOLCHAIN.read_text(encoding="utf-8")
        adapter_text = ADAPTER.read_text(encoding="utf-8")
        smoke_text = PACKAGED_SMOKE.read_text(encoding="utf-8")
        symbol_schema = json.loads(KOTLIN_SYMBOL_SCHEMA.read_text(encoding="utf-8"))
        symbol_properties = symbol_schema["$defs"]["symbol"]["properties"]
        if "constructor" not in symbol_properties["kind"]["enum"] or "constructor" not in symbol_properties["id"]["pattern"]:
            fail(failures, "API 0.2 Kotlin symbol schema omits constructor kind or identity")
        if build_text.count('kotlin-compiler-embeddable:2.0.21') != 2:
            fail(failures, "Kotlin compiler dependency pins must remain exact in compile/test configurations")
        for token in ('QUALIFIED_JDK_MAJOR = 21', 'QUALIFIED_KOTLIN_VERSION = "2.0.21"'):
            if token not in toolchain_text:
                fail(failures, f"toolchain policy token missing: {token}")
        if build_text.count('kotlinx-serialization-json:1.7.3') != 1:
            fail(failures, "Kotlin packaged serialization dependency pin must remain exact")
        for token in ('operation = "android"', 'operation = "compilerPluginSemantics"',
                      'operation = "expectActual"', 'operation = "generatedCodeMutation"',
                      'operation = "multiplatform"', 'refused("scriptSemantics", setOf("kts"))'):
            if token not in adapter_text:
                fail(failures, f"capability refusal token missing: {token}")
        for token in ('"kotlin-compiler-embeddable", "2.0.21"',
                      '"kotlin-script-runtime", "2.0.21"', '"kotlin-reflect", "1.6.10"',
                      '"kotlin-daemon-embeddable", "2.0.21"', '"trove4j", "1.0.20200330"',
                      '"kotlinx-coroutines-core-jvm", "1.6.4"', '"annotations", "13.0"'):
            if token not in smoke_text:
                fail(failures, f"qualified compiler-classpath pin missing: {token}")
        if "COMMAND_TIMEOUT_SECONDS = 180" not in smoke_text or smoke_text.count(
            "timeout=COMMAND_TIMEOUT_SECONDS"
        ) != 2:
            fail(failures, "packaged Kotlin qualification command timeout must remain bounded at 180 seconds")
    except Exception as problem:
        fail(failures, f"implementation projection cannot be inspected: {problem}")

    receipt = {
        "status": "FAILED" if failures else "PASSED",
        "schemaVersion": 1,
        "supportProjectionSha256": sha256(SUPPORT) if SUPPORT.exists() else None,
        "toolchainRows": len(toolchains) if isinstance(toolchains, list) else 0,
        "buildModelRows": len(models) if isinstance(models, list) else 0,
        "capabilityRows": len(shapes) if isinstance(shapes, list) else 0,
        "licenseRows": len(licenses),
        "sbomState": sbom.get("state") if isinstance(sbom, dict) else None,
        "failures": failures,
    }
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
