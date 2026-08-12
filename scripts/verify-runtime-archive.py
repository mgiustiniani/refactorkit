#!/usr/bin/env python3
"""Verify a RefactorKit native runtime archive without trusting extraction."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import queue
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import zipfile

MAX_ENTRIES = 20_000
MAX_EXPANDED_BYTES = 1_073_741_824
MAX_COMPRESSION_RATIO = 1_000
MAX_MCP_STREAM_BYTES = 1_048_576
MAX_MCP_LINE_BYTES = 1_048_576
MCP_RESPONSE_TIMEOUT_SECONDS = 30
MCP_SHUTDOWN_TIMEOUT_SECONDS = 20
MCP_DRAIN_TIMEOUT_SECONDS = 5
FIXED_TIMESTAMP = (1980, 2, 1, 0, 0, 0)
MCP_PROTOCOL_VERSION = "2024-11-05"
REQUIRED_MCP_TOOLS = {"project_scan", "preview_refactoring", "apply_refactoring", "rollback_refactoring"}
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
        "refactorkit/bin/refactorkit-mcp.bat" if windows else "refactorkit/bin/refactorkit-mcp",
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
            "refactorkit/bin/refactorkit-mcp",
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


class _BoundedCapture:
    def __init__(self, limit: int):
        self.limit = limit
        self.content = bytearray()
        self.total = 0
        self.truncated = False
        self.failure: BaseException | None = None
        self.lock = threading.Lock()

    def append(self, chunk: bytes) -> None:
        with self.lock:
            self.total += len(chunk)
            available = self.limit - len(self.content)
            if available > 0:
                self.content.extend(chunk[:available])
            if len(chunk) > available:
                self.truncated = True

    def mark_truncated(self) -> None:
        with self.lock:
            self.truncated = True

    def record_failure(self, failure: BaseException) -> None:
        with self.lock:
            self.failure = failure

    def snapshot(self) -> bytes:
        with self.lock:
            return bytes(self.content)


_END_OF_MCP_STDOUT = object()


def _offer_mcp_frame(frames: queue.Queue, frame: object, capture: _BoundedCapture) -> None:
    try:
        frames.put_nowait(frame)
    except queue.Full:
        capture.mark_truncated()


def _drain_mcp_stdout(
    stream,
    capture: _BoundedCapture,
    frames: queue.Queue,
    line_limit: int,
) -> None:
    try:
        while True:
            line = stream.readline(line_limit + 1)
            if not line:
                break
            capture.append(line)
            complete = line.endswith(b"\n")
            invalid_size_or_framing = len(line) > line_limit or not complete
            _offer_mcp_frame(frames, (line, invalid_size_or_framing), capture)
            if invalid_size_or_framing and not complete:
                while True:
                    remainder = stream.readline(line_limit + 1)
                    if not remainder:
                        break
                    capture.append(remainder)
                    if remainder.endswith(b"\n"):
                        break
    except BaseException as failure:
        capture.record_failure(failure)
    finally:
        _offer_mcp_frame(frames, _END_OF_MCP_STDOUT, capture)


def _drain_mcp_stderr(stream, capture: _BoundedCapture) -> None:
    try:
        while True:
            chunk = stream.read(8192)
            if not chunk:
                break
            capture.append(chunk)
    except BaseException as failure:
        capture.record_failure(failure)


def _mcp_diagnostics(capture: _BoundedCapture) -> str:
    return capture.snapshot().decode("utf-8", errors="replace")


def _send_mcp_message(process: subprocess.Popen, message: dict, stderr: _BoundedCapture) -> None:
    encoded = json.dumps(message, separators=(",", ":"), ensure_ascii=True).encode("utf-8") + b"\n"
    if len(encoded) > MAX_MCP_LINE_BYTES:
        raise AssertionError("MCP verifier request exceeds its line bound")
    try:
        if process.stdin is None:
            raise AssertionError("MCP launcher stdin is unavailable")
        process.stdin.write(encoded)
        process.stdin.flush()
    except (BrokenPipeError, OSError) as failure:
        raise AssertionError(
            f"MCP launcher closed stdin before the exchange completed: {_mcp_diagnostics(stderr)}"
        ) from failure


def _receive_mcp_response(
    process: subprocess.Popen,
    frames: queue.Queue,
    stderr: _BoundedCapture,
    expected_id: str,
    method: str,
    timeout: float,
) -> object:
    try:
        frame = frames.get(timeout=timeout)
    except queue.Empty as failure:
        state = f"exit {process.returncode}" if process.poll() is not None else "still running"
        raise AssertionError(
            f"MCP launcher response timeout for {method} ({state}): {_mcp_diagnostics(stderr)}"
        ) from failure
    if frame is _END_OF_MCP_STDOUT:
        raise AssertionError(
            f"MCP launcher exited before the {method} response: {_mcp_diagnostics(stderr)}"
        )
    line, invalid_size_or_framing = frame
    if invalid_size_or_framing:
        raise AssertionError(f"MCP launcher emitted an oversized or unterminated response for {method}")
    try:
        response = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise AssertionError(f"MCP launcher emitted a malformed response for {method}") from failure
    if not isinstance(response, dict) or response.get("jsonrpc") != "2.0" or response.get("id") != expected_id:
        raise AssertionError(f"MCP launcher emitted an invalid JSON-RPC envelope for {method}")
    if response.get("error") is not None or "result" not in response:
        raise AssertionError(f"MCP launcher returned an error or omitted result for {method}")
    return response["result"]


def _verify_mcp_initialize(result: object) -> None:
    if not isinstance(result, dict) or result.get("protocolVersion") != MCP_PROTOCOL_VERSION:
        raise AssertionError("MCP initialize returned an invalid protocol version")
    server_info = result.get("serverInfo")
    if not isinstance(server_info, dict) or server_info.get("name") != "refactorkit":
        raise AssertionError("MCP initialize returned invalid server information")


def _verify_mcp_tools(result: object) -> None:
    if not isinstance(result, dict) or not isinstance(result.get("tools"), list):
        raise AssertionError("MCP tools/list returned an invalid tool collection")
    names = {
        tool.get("name")
        for tool in result["tools"]
        if isinstance(tool, dict) and isinstance(tool.get("name"), str)
    }
    missing = REQUIRED_MCP_TOOLS - names
    if missing:
        raise AssertionError(f"MCP tools/list is missing required tools: {sorted(missing)}")


def _join_mcp_drains(
    threads: tuple[threading.Thread, threading.Thread],
    stdout: _BoundedCapture,
    stderr: _BoundedCapture,
) -> None:
    for thread in threads:
        thread.join(MCP_DRAIN_TIMEOUT_SECONDS)
        if thread.is_alive():
            raise AssertionError(f"MCP launcher {thread.name} did not drain within the bound")
    for label, capture in (("stdout", stdout), ("stderr", stderr)):
        if capture.failure is not None:
            raise AssertionError(f"MCP launcher {label} drain failed") from capture.failure


def _abort_mcp_process(process: subprocess.Popen, threads: tuple[threading.Thread, threading.Thread]) -> None:
    try:
        if process.stdin is not None and not process.stdin.closed:
            process.stdin.close()
    except OSError:
        pass
    if process.poll() is None:
        process.kill()
        try:
            process.wait(timeout=MCP_DRAIN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            pass
    for thread in threads:
        thread.join(MCP_DRAIN_TIMEOUT_SECONDS)
    for stream in (process.stdout, process.stderr):
        if stream is not None:
            try:
                stream.close()
            except OSError:
                pass


def verify_mcp_stdio(
    command: list[str],
    environment: dict[str, str],
    *,
    response_timeout: float = MCP_RESPONSE_TIMEOUT_SECONDS,
    shutdown_timeout: float = MCP_SHUTDOWN_TIMEOUT_SECONDS,
    output_limit: int = MAX_MCP_STREAM_BYTES,
) -> None:
    if response_timeout <= 0 or shutdown_timeout <= 0 or output_limit <= 0:
        raise ValueError("MCP verifier bounds must be positive")
    process = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=environment,
    )
    stdout = _BoundedCapture(output_limit)
    stderr = _BoundedCapture(output_limit)
    frames: queue.Queue = queue.Queue(maxsize=8)
    line_limit = min(MAX_MCP_LINE_BYTES, output_limit)
    stdout_thread = threading.Thread(
        target=_drain_mcp_stdout,
        args=(process.stdout, stdout, frames, line_limit),
        name="stdout",
        daemon=True,
    )
    stderr_thread = threading.Thread(
        target=_drain_mcp_stderr,
        args=(process.stderr, stderr),
        name="stderr",
        daemon=True,
    )
    threads = (stdout_thread, stderr_thread)
    for thread in threads:
        thread.start()

    completed = False
    try:
        initialize_id = "runtime-archive-initialize"
        _send_mcp_message(process, {
            "jsonrpc": "2.0",
            "id": initialize_id,
            "method": "initialize",
            "params": {
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "refactorkit-runtime-archive-verifier", "version": "1"},
            },
        }, stderr)
        initialize = _receive_mcp_response(
            process, frames, stderr, initialize_id, "initialize", response_timeout,
        )
        _verify_mcp_initialize(initialize)

        _send_mcp_message(process, {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {},
        }, stderr)
        tools_id = "runtime-archive-tools-list"
        _send_mcp_message(process, {
            "jsonrpc": "2.0",
            "id": tools_id,
            "method": "tools/list",
            "params": {},
        }, stderr)
        tools = _receive_mcp_response(
            process, frames, stderr, tools_id, "tools/list", response_timeout,
        )
        _verify_mcp_tools(tools)

        # MCP has no RefactorKit-specific shutdown method; EOF is its public clean shutdown signal.
        if process.stdin is None:
            raise AssertionError("MCP launcher stdin is unavailable during shutdown")
        process.stdin.close()
        try:
            returncode = process.wait(timeout=shutdown_timeout)
        except subprocess.TimeoutExpired as failure:
            raise AssertionError("MCP launcher did not shut down cleanly after EOF") from failure
        _join_mcp_drains(threads, stdout, stderr)
        if stdout.truncated or stderr.truncated:
            raise AssertionError(
                f"MCP launcher output exceeded the {output_limit}-byte per-stream bound"
            )
        if returncode != 0:
            raise AssertionError(
                f"MCP launcher exited nonzero ({returncode}): {_mcp_diagnostics(stderr)}"
            )
        while True:
            try:
                extra = frames.get_nowait()
            except queue.Empty:
                break
            if extra is not _END_OF_MCP_STDOUT:
                raise AssertionError("MCP launcher emitted unexpected extra stdout")
        completed = True
    finally:
        if not completed:
            _abort_mcp_process(process, threads)
        else:
            for stream in (process.stdout, process.stderr):
                if stream is not None:
                    stream.close()


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

    mcp_launcher = root / "refactorkit" / "bin" / ("refactorkit-mcp.bat" if windows else "refactorkit-mcp")
    mcp_command = [str(mcp_launcher)]
    if windows:
        mcp_command = ["cmd", "/d", "/c", *mcp_command]
    verify_mcp_stdio(mcp_command, environment)
    print("Extracted MCP launcher verified: initialize, tools/list, EOF shutdown (exit 0)")


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
