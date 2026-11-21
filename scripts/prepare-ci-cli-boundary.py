#!/usr/bin/env python3
"""Provide the installed-CLI tripwire in a clean qualification runner."""

import argparse
from pathlib import Path


TRIPWIRE = b"#!/bin/sh\nprintf '%s\\n' 'INSTALLED_BOUNDARY_TRIPWIRE_MUST_NOT_RUN' >&2\nexit 97\n"


def prepare(runner_home: Path) -> Path:
    home = runner_home.resolve(strict=True)
    if not home.is_dir():
        raise ValueError("runner home must be a directory")
    parent = home
    for segment in (".local", "share"):
        parent = parent / segment
        if parent.is_symlink():
            raise ValueError(f"runner home boundary contains a symbolic directory: {parent}")
        parent.mkdir(exist_ok=True)

    root = parent / "refactorkit"
    root.mkdir(mode=0o700)
    binary_dir = root / "bin"
    launcher = binary_dir / "refactorkit"
    try:
        binary_dir.mkdir(mode=0o700)
        with launcher.open("xb") as fixture:
            fixture.write(TRIPWIRE)
        launcher.chmod(0o755)
    except OSError:
        launcher.unlink(missing_ok=True)
        if binary_dir.is_dir():
            binary_dir.rmdir()
        root.rmdir()
        raise
    return launcher


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runner-home", type=Path, required=True)
    options = parser.parse_args()
    try:
        print(prepare(options.runner_home))
    except (OSError, ValueError) as problem:
        parser.exit(1, f"CLI boundary preparation refused: {problem}\n")
