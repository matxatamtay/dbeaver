# Evidence and privacy

Each run owns a bounded directory:

```text
Test Studio/Runs/<run-id>/
  manifest.json
  plan.snapshot.json
  variables.masked.json
  steps/
  attachments/
  report.json
  cleanup.json
  reports/
```

The canonical JSON report is authoritative. JUnit, HTML, and Markdown are derived renderers and cannot change the run result.

## Limits

- 50 MiB per run.
- 10 MiB per attachment.
- 25 snapshots per run.
- Bounded step results and report reads.
- Retention supports `keep_last`, age cutoff, and pinned runs.

Every attachment receives a SHA-256 checksum. Runs left in `running` or `queued` state across a restart are recovered as `aborted`.

## Redaction

Plans reject secret-like fields. Sensitive generated variables are replaced with `***`. Connection summaries exclude credential fields. Evidence and support bundles must not contain secure storage values. Screenshots are optional and carry an explicit warning because visible grids or editors may contain sensitive values.

Sharing evidence remains a user decision. The engine cannot guarantee that arbitrary SQL result text is non-sensitive, so result previews remain bounded and providers should apply database-specific masking where available.
