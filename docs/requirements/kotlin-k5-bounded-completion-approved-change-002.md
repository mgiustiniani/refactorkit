# Kotlin K5 bounded completion approved change 002

Status: incorporated before replacement native qualification.

## Superseded candidate evidence

- superseded aggregate requirement SHA-256: `c7563bbfa0737d27d69aa9cda8ae6249f6886b298b81d6a1330e981fd788ed4a`
- failed local candidate: `ce65175a4aed6c12920792dd283a6a4480272c86`
- independent adversarial audit SHA-256: `48f3815f314ddb88c41aaf0300972bcc55766eef68a5bec0c61241798c92203e`
- audit: `docs/requirements/evidence/v0.7.0-k5-pre-native-audit-ce65175-fail-48f3815f314ddb88c41aaf0300972bcc55766eef68a5bec0c61241798c92203e.md`

Executable probes:

| Boundary | SHA-256 | Evidence |
|---|---|---|
| external enum entry rebound | `4cee58ffb423157eaaca28ecfeb531342f82d2a599ad4937b0664a30f2b07de9` | `docs/requirements/evidence/probe-k5-enum-rebound-4cee58ffb423157eaaca28ecfeb531342f82d2a599ad4937b0664a30f2b07de9.py` |
| aliased property / enum rebound | `bcd83f9abc979141657f4095fb7d17b24980351a831cd1fcb426c7fd73de81d0` | `docs/requirements/evidence/probe-k5-organize-alias-property-enum-bcd83f9abc979141657f4095fb7d17b24980351a831cd1fcb426c7fd73de81d0.py` |
| custom Maven generated root | `ce9b281e84e3e9e7d3fabf3ffd52a38cef347f3b35778e43b55d2f41eed76351` | `docs/requirements/evidence/probe-k5-custom-generated-param-ce9b281e84e3e9e7d3fabf3ffd52a38cef347f3b35778e43b55d2f41eed76351.py` |
| Maven all-open plugin | `34807117871815749ef9d9e06165c83341614b9453d95e85560eec4e1d7f4735` | `docs/requirements/evidence/probe-k5-maven-allopen-34807117871815749ef9d9e06165c83341614b9453d95e85560eec4e1d7f4735.py` |

No native run or promotion followed this failed local audit.

- exact 23-case GREEN SHA-256: `07516d18391337648be2f27e0ef85a25c265b2d4ef1708489c1ed4d1394ea926`
- exact 23-case GREEN: `docs/requirements/evidence/v0.7.0-k5-completion-second-audit-23-case-green-07516d18391337648be2f27e0ef85a25c265b2d4ef1708489c1ed4d1394ea926.log`

## Approved non-weakening corrections

The leaf requirements already require complete import evidence and separate
generated/plugin refusal. They remain unchanged. The correction:

1. treats unresolved external enum-entry field identity and property-alias
   identity as typed incomplete usage evidence, so a compiling shadow cannot be
   called unused;
2. classifies effective Maven build-helper source roots below `target`,
   `build/generated`, or `build/tmp/kapt3` as generated even when their terminal
   directory is custom;
3. projects Maven Kotlin `<compilerPlugins>`, `<pluginOptions>`, compiler-plugin
   dependencies, kapt/KSP goals and KSP/kapt build plugins into exact compiler
   plugin IDs, causing the existing Kotlin/JVM projector to return
   `kotlin.compilerPluginsUnsupported`; and
4. hash-binds the exact K5 capability/help compatibility resources and their
   local-Linux Cucumber glue in native subject maps without falsely relabeling
   their explicit platform boundary.

The completion oracle grows from 22 to 23 cases; the generated-source and
compiler-plugin cases are strengthened in place. The replacement aggregate
requirement SHA-256 is
`7bb83fdf5a1f89ff358c6662bcd5ecfb1f4d086e1034f725a40a7652291b3197`.

These changes add refusal authority only. They do not claim raw JVM field or enum
modeling, property-alias mutation, arbitrary generated-root discovery, compiler
plugin execution, or cross-platform Java catalogue qualification. Replacement
four-host receipts and fresh independent review remain mandatory.
