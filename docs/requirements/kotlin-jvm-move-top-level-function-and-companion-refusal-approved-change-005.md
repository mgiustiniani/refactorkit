# Kotlin/JVM move-next requirement change 005

Status: incorporated before replacement candidate commit and native qualification.

## Failed candidate evidence

- unchanged sealed requirement SHA-256: `52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`
- failed local candidate: `a8822c28070956f56936bdc7b82888fc4a54ed06`
- independent adversarial audit SHA-256: `d1dee7d777b99817192d8f039c7b2a0e5dbeef092861f8f471232286acb43d2e`
- audit: `docs/requirements/evidence/v0.7.0-k5-pre-native-audit-a8822c2-fail-d1dee7d777b99817192d8f039c7b2a0e5dbeef092861f8f471232286acb43d2e.md`
- typealias rebound probe SHA-256: `df8947bf38df4fb7699be2de9bb13265d334804fe7003840c034de3901cb8aaa`
- typealias rebound probe: `docs/requirements/evidence/probe-a8822-move-typealias-rebound-df8947bf38df4fb7699be2de9bb13265d334804fe7003840c034de3901cb8aaa.py`
- Maven `-Xplugin` probe SHA-256: `cc04cc67a245fcde1fd35312ce79ebb76dc9c78d6a2de50a1e3a1b6ea7d02a84`
- Maven `-Xplugin` probe: `docs/requirements/evidence/probe-a8822-maven-xplugin-move-cc04cc67a245fcde1fd35312ce79ebb76dc9c78d6a2de50a1e3a1b6ea7d02a84.py`
- exact 25-case GREEN SHA-256: `ca1357ef0569e6010d4184441e1c44abd2263a227bb03cc43637929a06572927`
- exact 25-case GREEN: `docs/requirements/evidence/kotlin-jvm-move-typealias-xplugin-25-case-green-ca1357ef0569e6010d4184441e1c44abd2263a227bb03cc43637929a06572927.log`

## Approved non-weakening corrections

The sealed requirement already requires exact outbound type identity and excludes
plugin-dependent semantics. Candidate `a8822c2` omitted a user-defined typealias
whose source spelling differed from its FIR-expanded JVM type, so a same-name
alias in the destination package could cleanly change the moved function's type
binding. It also omitted Kotlin Maven compiler plugins declared through
`<args><arg>-Xplugin=…</arg></args>`.

Compiler usage extraction now detects FIR abbreviated types and
`FirTypeAliasSymbol` qualifiers and returns
`kotlin.usageTypeAliasUnsupported`; no typealias form acquires mutation
authority. The Maven effective-model projection maps `-Xplugin` arguments to the
stable non-path plugin ID `external-xplugin`, causing the existing
`kotlin.compilerPluginsUnsupported` refusal. Both refusals occur before a patch
or transaction can be written.

The exact move oracle grows from 24 to 25 cases. The packaged move smoke also
requires no-write typealias and Maven `-Xplugin` refusals. The move receipt subject
map now binds the Maven scanner/projector and Kotlin build-model implementation
and test that provide this operation dependency.

No typealias mutation, compiler-plugin execution, new declaration shape, or
broader move form is authorized. A replacement four-host run and fresh
independent post-native review remain mandatory.
