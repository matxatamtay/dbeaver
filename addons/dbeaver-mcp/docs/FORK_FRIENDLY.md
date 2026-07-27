# Fork-friendly build contract

1. `dbeaver-mcp` is the only source-of-truth repository.
2. DBeaver worktrees are disposable compatibility targets.
3. Overlay paths are additive: new bundles, features, repositories, and one releng aggregator.
4. Overlay/build scripts fail when tracked upstream files change.
5. DBeaver API access that is likely to change belongs in compatibility bundles.
6. Engine, plan model, assertions, evidence, and reports do not import DBeaver UI implementation classes.
7. Releases support the current and previous DBeaver minor line; `devel` is a best-effort compile check.
