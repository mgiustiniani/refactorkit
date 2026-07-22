#!/usr/bin/env python3
"""Verify a RefactorKit native runtime archive without trusting extraction."""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile

MAX_ENTRIES = 20_000
MAX_EXPANDED_BYTES = 1_073_741_824
MAX_COMPRESSION_RATIO = 1_000
FIXED_TIMESTAMP = (1980, 2, 1, 0, 0, 0)
REQUIRED_MODULES = {"java.base", "java.compiler", "java.logging", "java.xml", "jdk.unsupported", "jdk.zipfs"}
PLATFORMS = {"linux-x86_64", "windows-x86_64", "macos-x86_64", "macos-aarch64"}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_checksum(archive: pathlib.Path, checksum: pathlib.Path) -> None:
    fields = checksum.read_text(encoding="ascii").strip().split()
    if len(fields) != 2 or pathlib.PurePosixPath(fields[1].lstrip("*").replace("\\", "/")).name != archive.name:
        raise AssertionError("checksum must contain exactly SHA-256 and the archive basename")
    if len(fields[0]) != 64 or any(character not in "0123456789abcdefABCDEF" for character in fields[0]):
        raise AssertionError("checksum does not contain a canonical SHA-256")
    actual = sha256(archive)
    if actual != fields[0].lower():
        raise AssertionError(f"checksum mismatch: expected {fields[0].lower()}, got {actual}")


def safe_entries(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    entries = archive.infolist()
    if not entries or len(entries) > MAX_ENTRIES:
        raise AssertionError(f"archive entry count is outside 1..{MAX_ENTRIES}")
    names: dict[str, zipfile.ZipInfo] = {}
    folded: set[str] = set()
    expanded = 0
    for entry in entries:
        name = entry.filename
        path = pathlib.PurePosixPath(name)
        if "\\" in name or path.is_absolute() or not path.parts or path.parts[0] != "refactorkit":
            raise AssertionError(f"unsafe/non-canonical archive path: {name!r}")
        if any(part in {"", ".", ".."} for part in path.parts):
            raise AssertionError(f"archive path contains traversal/empty component: {name!r}")
        if name in names or name.casefold() in folded:
            raise AssertionError(f"duplicate or case-fold-colliding archive path: {name!r}")
        names[name] = entry
        folded.add(name.casefold())
        mode = entry.external_attr >> 16
        if stat.S_IFMT(mode) == stat.S_IFLNK:
            raise AssertionError(f"archive contains a symbolic link: {name!r}")
        expanded += entry.file_size
        if expanded > MAX_EXPANDED_BYTES:
            raise AssertionError("archive expanded-size limit exceeded")
        if entry.compress_size and entry.file_size > entry.compress_size * MAX_COMPRESSION_RATIO:
            raise AssertionError(f"suspicious compression ratio: {name!r}")
        if entry.date_time != FIXED_TIMESTAMP:
            raise AssertionError(f"non-reproducible timestamp on {name!r}: {entry.date_time}")
    return names


def require_layout(entries: dict[str, zipfile.ZipInfo], platform: str) -> None:
    windows = platform.startswith("windows-")
    required = {
        "refactorkit/runtime/release",
        "refactorkit/bin/refactorkit.bat" if windows else "refactorkit/bin/refactorkit",
        "refactorkit/bin/refactorkit-daemon.bat" if windows else "refactorkit/bin/refactorkit-daemon",
        "refactorkit/runtime/bin/java.exe" if windows else "refactorkit/runtime/bin/java",
    }
    missing = required - entries.keys()
    if missing:
        raise AssertionError(f"archive layout is missing: {sorted(missing)}")
    if not any(name.startswith("refactorkit/lib/") and name.endswith(".jar") for name in entries):
        raise AssertionError("archive contains no application JARs")
    if not windows:
        executable = {
            "refactorkit/bin/refactorkit",
            "refactorkit/bin/refactorkit-daemon",
            "refactorkit/runtime/bin/java",
        }
        for name in executable:
            mode = entries[name].external_attr >> 16
            if mode & 0o111 == 0:
                raise AssertionError(f"required executable bit is absent: {name}")


def verify_modules(archive: zipfile.ZipFile) -> None:
    release = archive.read("refactorkit/runtime/release").decode("utf-8")
    modules_line = next((line for line in release.splitlines() if line.startswith("MODULES=")), None)
    if modules_line is None:
        raise AssertionError("jlink release metadata has no MODULES entry")
    modules = set(modules_line.partition("=")[2].strip().strip('"').split())
    missing = REQUIRED_MODULES - modules
    if missing:
        raise AssertionError(f"embedded runtime modules missing: {sorted(missing)}")


def binary_architecture(binary: bytes, platform: str) -> str:
    if platform.startswith("linux-"):
        if binary[:4] != b"\x7fELF" or len(binary) < 20:
            raise AssertionError("runtime java launcher is not ELF")
        byteorder = "little" if binary[5] == 1 else "big"
        machine = int.from_bytes(binary[18:20], byteorder)
        return {62: "x86_64", 183: "aarch64"}.get(machine, f"elf-{machine}")
    if platform.startswith("windows-"):
        if binary[:2] != b"MZ" or len(binary) < 64:
            raise AssertionError("runtime java launcher is not PE")
        offset = int.from_bytes(binary[60:64], "little")
        if binary[offset:offset + 4] != b"PE\0\0":
            raise AssertionError("runtime java launcher has invalid PE signature")
        machine = int.from_bytes(binary[offset + 4:offset + 6], "little")
        return {0x8664: "x86_64", 0xAA64: "aarch64"}.get(machine, f"pe-{machine}")
    magic = binary[:4]
    if magic not in {b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf"}:
        raise AssertionError("runtime java launcher is not 64-bit Mach-O")
    endian = "little" if magic == b"\xcf\xfa\xed\xfe" else "big"
    cpu = int.from_bytes(binary[4:8], endian)
    return {0x01000007: "x86_64", 0x0100000C: "aarch64"}.get(cpu, f"macho-{cpu}")


def verify_architecture(archive: zipfile.ZipFile, platform: str) -> None:
    launcher = "refactorkit/runtime/bin/java.exe" if platform.startswith("windows-") else "refactorkit/runtime/bin/java"
    actual = binary_architecture(archive.read(launcher), platform)
    expected = platform.split("-", 1)[1]
    if actual != expected:
        raise AssertionError(f"runtime architecture mismatch: expected {expected}, got {actual}")


def restore_archive_permissions(root: pathlib.Path, entries: dict[str, zipfile.ZipInfo]) -> None:
    if os.name == "nt":
        return
    for name, entry in entries.items():
        target = root.joinpath(*pathlib.PurePosixPath(name).parts)
        if target.exists() and not entry.is_dir():
            target.chmod((entry.external_attr >> 16) & 0o777)


def execute_extracted(root: pathlib.Path, platform: str) -> None:
    windows = platform.startswith("windows-")
    launcher = root / "refactorkit" / "bin" / ("refactorkit.bat" if windows else "refactorkit")
    command = [str(launcher), "version"]
    if windows:
        command = ["cmd", "/d", "/c", *command]
    environment = os.environ.copy()
    environment.pop("JAVA_HOME", None)
    result = subprocess.run(command, text=True, capture_output=True, timeout=60, env=environment)
    if result.returncode != 0 or "RefactorKit" not in result.stdout or "API" not in result.stdout:
        raise AssertionError(f"extracted launcher failed ({result.returncode}):\n{result.stdout}\n{result.stderr}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=pathlib.Path)
    parser.add_argument("checksum", type=pathlib.Path)
    parser.add_argument("--platform", required=True, choices=sorted(PLATFORMS))
    parser.add_argument("--no-execute", action="store_true")
    args = parser.parse_args()
    verify_checksum(args.archive, args.checksum)
    with zipfile.ZipFile(args.archive) as archive:
        entries = safe_entries(archive)
        require_layout(entries, args.platform)
        verify_modules(archive)
        verify_architecture(archive, args.platform)
        with tempfile.TemporaryDirectory(prefix="refactorkit-archive-verify-") as temporary:
            root = pathlib.Path(temporary)
            archive.extractall(root)
            restore_archive_permissions(root, entries)
            if not args.no_execute:
                execute_extracted(root, args.platform)
    print(f"Runtime archive verified: {args.archive.name} ({args.platform})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Runtime archive verification failed: {error}", file=sys.stderr)
        raise
