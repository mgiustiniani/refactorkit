#!/usr/bin/env bash
set -euo pipefail

readonly archive_name='LLVM-22.1.8-Linux-X64.tar.xz'
readonly archive_root='LLVM-22.1.8-Linux-X64'
readonly archive_sha256='df0e1ecf16caf3489a272a5eea4eec9b0d82878f6477fa309504f918a0006384'
readonly archive_url='https://github.com/llvm/llvm-project/releases/download/llvmorg-22.1.8/LLVM-22.1.8-Linux-X64.tar.xz'
readonly temp_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
[[ "$temp_parent" == /* && -d "$temp_parent" && ! -L "$temp_parent" ]] || {
  printf 'Refusing non-absolute, missing or symbolic qualification temp directory: %s\n' "$temp_parent" >&2
  exit 1
}
temp_root="$(mktemp -d "${temp_parent}/refactorkit-clang-22.1.8.XXXXXX")"
readonly temp_root
readonly toolchain_root="$temp_root"
readonly usr_root="${toolchain_root}/usr"
readonly archive="${temp_root}/${archive_name}"

# Runtime provenance requires direct directory ancestors. Check the staging
# location before downloading or extracting any archive bytes.
ancestor="$toolchain_root"
while [[ "$ancestor" != '/' ]]; do
  [[ -d "$ancestor" && ! -L "$ancestor" ]] || {
    printf 'Refusing symlink or non-directory toolchain ancestor: %s\n' "$ancestor" >&2
    exit 1
  }
  ancestor="$(dirname "$ancestor")"
done

curl --fail --location --retry 3 --output "$archive" "$archive_url"
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --strict

mkdir -p "$usr_root"
tar -xJf "$archive" --strip-components=1 -C "$usr_root" \
  "${archive_root}/bin/clang-22" \
  "${archive_root}/bin/clangd" \
  "${archive_root}/bin/clang-format" \
  "${archive_root}/lib/libclang-cpp.so.22.1" \
  "${archive_root}/lib/clang/22" \
  "${archive_root}/include/llvm/Support/LICENSE.TXT"
rm -f "$archive"
test -d "$usr_root/bin"
test -d "$usr_root/lib/clang/22/include"
test -s "$usr_root/include/llvm/Support/LICENSE.TXT"

# The archive members are stripped into a real usr tree rather than exposed
# through a symlink.
for directory in "$usr_root" "$usr_root/bin" "$usr_root/lib" "$usr_root/lib/clang" "$usr_root/lib/clang/22" "$usr_root/include"; do
  [[ -d "$directory" && ! -L "$directory" ]] || {
    printf 'Refusing symlink or missing toolchain directory: %s\n' "$directory" >&2
    exit 1
  }
done

readonly clang="${toolchain_root}/usr/bin/clang-22"
readonly clangd="${toolchain_root}/usr/bin/clangd"
readonly clang_format="${toolchain_root}/usr/bin/clang-format"

readonly clang_cpp="$usr_root/lib/libclang-cpp.so.22.1"

printf '%s  %s\n' \
  '31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113' "$clang" \
  'aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324' "$clangd" \
  'b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf' "$clang_format" \
  'a99d309f3cadea7fd7203ebec873bc165fc0c9ee0694ba4015c9902a5debf3a3' "$clang_cpp" \
  | sha256sum --check --strict

[[ "$("$clang" --version --no-default-config | sed -n '1p')" == clang\ version\ 22.1.8\ * ]]
[[ "$("$clangd" --version | sed -n '1p')" == clangd\ version\ 22.1.8\ * ]]
[[ "$("$clang_format" --version | sed -n '1p')" == clang-format\ version\ 22.1.8\ * ]]
[[ "$("$clang" -dumpmachine --no-default-config)" == x86_64-*linux* ]]
readonly resource_dir="$("$clang" -print-resource-dir --no-default-config)"
[[ "$(realpath "$resource_dir")" == "$(realpath "${toolchain_root}/usr/lib/clang/22")" ]]
test -f "${resource_dir}/include/stddef.h"

printf 'REFACTORKIT_C_QUALIFICATION_TOOLCHAIN=%s\n' "$toolchain_root" >> "$GITHUB_ENV"
