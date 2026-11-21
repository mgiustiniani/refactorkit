"""Version and subject JAR checks shared by native Kotlin receipt finalizers."""

from pathlib import Path
import re


HISTORICAL_VERSION = "0.7.0"
VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+"


def package_version(repository: Path, from_source: bool) -> str:
    if not from_source:
        return HISTORICAL_VERSION
    build = repository / "build.gradle.kts"
    versions = re.findall(r'^\s*version\s*=\s*"([^"]+)"\s*$', build.read_text(encoding="utf-8"), re.MULTILINE)
    if len(versions) != 1 or not re.fullmatch(VERSION_PATTERN, versions[0]):
        raise ValueError("the source build must declare one literal package version")
    return versions[0]


def subject_jars(
    package_root: Path, historical_jars: set[str], version: str, from_source: bool, missing_message: str,
) -> dict[str, Path]:
    prefixes = {name.removesuffix(HISTORICAL_VERSION + ".jar") for name in historical_jars}
    library = package_root / "lib"
    expected = {f"{prefix}{version}.jar" for prefix in prefixes}
    if from_source:
        subject = re.compile(rf"(?:{'|'.join(map(re.escape, sorted(prefixes)))}){VERSION_PATTERN}\.jar")
        foreign = sorted(path.name for path in library.glob("refactorkit-*.jar")
                         if subject.fullmatch(path.name) and path.name not in expected)
        if foreign:
            raise ValueError(f"mixed-version packaged subject JARs: {foreign}")
    jars = {name: library / name for name in sorted(expected)}
    missing = [name for name, path in jars.items() if not path.is_file()]
    if missing:
        raise ValueError(f"{missing_message}: {missing}")
    return jars
