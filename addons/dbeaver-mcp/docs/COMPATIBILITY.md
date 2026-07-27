# DBeaver compatibility policy

`dbeaver-mcp` is the source repository. DBeaver checkouts are disposable targets.

The compatibility matrix is `config/compatibility-matrix.json`:

- current stable: required;
- previous minor: required;
- `devel`: weekly best-effort.

## Update procedure

1. Update the refs in the matrix.
2. Let `upstream-compatibility.yml` build all targets.
3. Read the generated compatibility reports.
4. If an API changed, patch only the compatibility bundle or add a new version-specific bundle.
5. Run P2 install, uninstall, upgrade, rollback, and runtime smoke.
6. Confirm the disposable DBeaver worktree has zero tracked changes.

## Boundary rules

- DBeaver model, registry, and UI implementation imports are allowed only in `org.jkiss.dbeaver.teststudio.compat.*`.
- Studio core, AI, reports, assertions, and database adapters cannot import DBeaver implementation APIs.
- No `.internal.` imports are allowed.
- UI communicates through `TestStudioApi`, not runner internals.
- Existing DBeaver product, perspective, core bundle, and feature files are never patched.

`validate.sh` enforces these rules. `compat-report.sh` records the exact upstream commit, bundle versions, API imports, matrix, and capability gaps.
