# Kotlin CLI change-signature mode compatibility

Status: candidate compatibility correction bound to K5 completion qualification.

The pre-existing packaged Kotlin CLI accepted shared Java/Kotlin add-parameter
arguments `--symbol --type --name --default` without an explicit `--operation`.
The bounded parameter-rename slice adds `--old-name --new-name`; defaulting every
operation-less request to rename would break the already qualified add-parameter
surface.

The parser therefore selects mode without semantic guessing:

1. exact `--operation` wins and must be `rename-parameter` or `add-parameter`;
2. without it, the presence of any add-only argument (`--type`, `--name`,
   `--default`) selects add-parameter;
3. otherwise rename-parameter is selected and its exact required arguments are
   validated; and
4. mixed rename/add argument families without `--operation` are rejected.

This preserves old packaged add-parameter bytes while allowing the new bounded
rename form. It does not broaden either planner, infer symbols or types, weaken
external-risk approval, or authorize any additional signature operation.
`scripts/smoke-packaged-kotlin.py` remains the executable backward-compatibility
oracle; `scripts/smoke-packaged-k5-completion.py` is the rename surface oracle.
