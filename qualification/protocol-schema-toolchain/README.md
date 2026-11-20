# Protocol schema qualification tools

This directory owns pinned test infrastructure. [ARC42](../../docs/arc42/appendix-requirements.adoc)
owns integrated acceptance; these packages are not production dependencies.

Install the exact lock from this directory with:

```sh
npm ci --ignore-scripts --no-audit --no-fund
```

`ajv` retains Ajv 6.15.0 for existing bounded compatibility profiles.
`ajv2020` aliases Ajv 8.20.0. The SDK27 context schema Story uses its
[`dist/2020` entry point](https://ajv.js.org/json-schema.html#draft-2020-12)
to validate the unchanged public v2–v7 Draft 2020-12 schemas, including native
responses, retained bounds and negative keyword controls.

The Story selects this owned `ajv2020` directory by default. An explicit
`REFACTORKIT_SCHEMA_AJV2020_MODULE` can select an installed module of the exact
pinned version. Installation is a separate preparation step. The existing
`REFACTORKIT_SCHEMA_AJV_MODULE` still selects the legacy Ajv6 profile.
