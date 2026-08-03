# DBeaver Desktop MCP

This plugin starts a loopback-only MCP server inside DBeaver Desktop after the workbench starts.

A control panel is available at **Window → Preferences → User Interface → MCP Server**. It provides live Start/Stop controls, automatic-start configuration, port and bearer-token settings, current status, and an MCP-specific log viewer.

## Endpoints

- MCP: `http://127.0.0.1:3846/mcp`
- Health: `http://127.0.0.1:3846/healthz`

## Phase 0 additive architecture

The MCP implementation is packaged as the independently installable `org.jkiss.dbeaver.mcp.feature`. It no longer requires edits to existing DBeaver products, core manifests, or upstream features. A standalone P2 update site is produced by:

```bash
../dbeaver-common/mvnw package -f releng/org.jkiss.dbeaver.mcp.build/pom.xml -DskipTests
```

The resulting installable repository is:

```text
product/repositories/org.jkiss.dbeaver.mcp.repository/target/
org.jkiss.dbeaver.mcp.updateSite-2.0.1-SNAPSHOT.zip
```

The runtime registers built-in and external `DBeaverMcpToolProvider` implementations through the `org.jkiss.dbeaver.mcp.toolProvider` Eclipse extension point. Providers receive a shared context containing the bounded job manager and active policy. One provider failure is logged without preventing the remaining providers or the MCP server from starting.

## Tools

New clients should prefer the compact tools. Phase 0 facades forward to the same handlers used by legacy tools; Phase 2 facades operate native DBeaver Data Editors and the shared job API:

- `dbeaver_workspace`
- `dbeaver_sql`
- `dbeaver_database`
- `dbeaver_change`
- `dbeaver_job`
- `dbeaver_data`
- `dbeaver_transfer`
- `dbeaver_task`
- `dbeaver_project`
- `dbeaver_environment`
- `dbeaver_visual`
- `dbeaver_admin`
- `dbeaver_test`
- `dbeaver_workbench`
- `dbeaver_quality`

Call a compact facade with `action=discover` to retrieve its available actions and the complete forwarded legacy schemas. The original 46 tools remain registered for compatibility.

## Phase 2 data workflows

`dbeaver_data` operates the native `ResultSetViewer` used by DBeaver's Data Editor. It can open a table or view, list and inspect open editors, fetch bounded pages, apply WHERE/order filters, request the next segment, refresh data, and stage cell edits, inserts, or deletes. Changes remain visible and dirty inside DBeaver until `save_changes` is called. Saving runs as a shared MCP job and keeps DBeaver's native save-preview/confirmation behavior. `reject_changes` requires an explicit confirmation flag.

`dbeaver_transfer` supports bounded CSV, JSON, and best-effort SQL exports. CSV and JSON imports are parsed in a background MCP job and staged as new rows in the native Data Editor; they do not write the database automatically. The operator must inspect `pending_changes` and call `save_changes` separately.

Transfer paths are restricted to a configured root. Relative paths resolve below that root, parent traversal and escaping symbolic links are rejected, and existing output files require `overwrite=true`. Sensitive-looking values are masked by default during export. Passwords, tokens, card numbers, and national identifiers remain masked even when ordinary masking is disabled.

## Phase 3 desktop workflows

Phase 3 adds native task/scheduler control, project SQL scripts, driver and preference inspection, ERD workflows, database session/lock monitoring, and bounded PostgreSQL administration operations. Mutations require explicit flags and native DBeaver confirmation dialogs.

`dbeaver_task` uses each project's native task manager and active scheduler. `dbeaver_project` confines scripts to project `Scripts` folders with traversal, symlink, and one-megabyte limits. `dbeaver_environment` refuses credential-like preference keys. `dbeaver_visual` operates native ERD editors and exports below the transfer root. `dbeaver_admin` uses session-manager adapters and a typed PostgreSQL administration allowlist.

## Phase 4 tester platform

`dbeaver_test` composes existing MCP tools into bounded test cases without bypassing their policy checks or native confirmations. It supports RFC 6901 JSON-pointer assertions, retries, asynchronous suites, condition waits, schema-drift thresholds, migration rehearsals, and up to 25 in-memory snapshots capped at two MiB each. Non-read-only target tools require `allow_non_read_only=true`; the target tool's own scopes and confirmations remain authoritative.

Test suites, waits, and migration rehearsals run through the shared MCP job manager. Tester recursion and direct testing of the job facade are rejected. Snapshot comparisons return at most 200 structured differences.

## Phase 5 coverage expansion

`dbeaver_workbench` exposes bounded workbench state, editor/view/perspective discovery, Eclipse commands, background jobs, and notification settings. Generic editor saves require `ui`, `workspace`, and `data_write`. Commands outside a navigation-only allowlist require `allow_unsafe_command=true`, `admin` scope, and a native confirmation dialog.

`dbeaver_quality` adds connection health matrices, sanitized environment comparison, read-only query regression across up to 20 connections, schema contracts, anomaly scans, diagnostics, metadata-only audit metrics, and support-bundle export. Support bundles are written below the transfer root and exclude credentials, user names, connection URLs, SQL, tool arguments, query rows, and raw tool results.

### Workspace and SQL editors

- `dbeaver_status`
- `dbeaver_list_connections`
- `dbeaver_open_sql_editor`
- `dbeaver_insert_sql`
- `dbeaver_replace_sql`
- `dbeaver_append_sql`
- `dbeaver_focus_editor`
- `dbeaver_save_sql_snippet`
- `dbeaver_select_connection`
- `dbeaver_propose_sql`
- `dbeaver_get_active_editor`
- `dbeaver_get_current_selection`

### Confirmed execution and results

- `dbeaver_prepare_sql_execution`
- `dbeaver_execute_sql`
- `dbeaver_cancel_sql_execution`
- `dbeaver_get_last_result`
- `dbeaver_fetch_result`
- `dbeaver_get_last_queries`
- `dbeaver_profile_query`
- `dbeaver_explain_query`

### Transactions

- `dbeaver_get_transaction_status`
- `dbeaver_begin_transaction`
- `dbeaver_commit`
- `dbeaver_rollback`

### Database discovery

- `dbeaver_database_summary`
- `dbeaver_list_objects`
- `dbeaver_find_objects`
- `dbeaver_describe_object`
- `dbeaver_get_object_ddl`
- `dbeaver_get_documentation`
- `dbeaver_understand_database`

### Rules, behavior, and lineage

- `dbeaver_get_business_rules`
- `dbeaver_get_dependencies`
- `dbeaver_trace_lineage`
- `dbeaver_get_call_graph`
- `dbeaver_explain_trigger_flow`
- `dbeaver_explain_data_change`

### Data, performance, and security

- `dbeaver_sample_rows`
- `dbeaver_profile_table`
- `dbeaver_find_sensitive_data`
- `dbeaver_analyze_indexes`
- `dbeaver_get_permissions`
- `dbeaver_security_summary`

### Change testing

- `dbeaver_compare_schemas`
- `dbeaver_analyze_change`
- `dbeaver_simulate_change`

Every discovery result reports `coverage` and `blind_spots` where the driver, current database user, dynamic SQL, or generic DBeaver model cannot provide complete information.

## Operator workflow and safety model

The model cannot execute arbitrary SQL directly. The normal flow is:

1. Use `dbeaver_propose_sql` or the editor tools so the SQL is visible in DBeaver.
2. Call `dbeaver_prepare_sql_execution` with an editor ID, or with an explicit connection and SQL string.
3. DBeaver shows a native confirmation dialog containing the exact connection, SQL, source, and read/write risk.
4. Only when the user clicks **Run** does DBeaver issue a one-time approval ID. It expires after five minutes and is bound to the exact SQL and connection.
5. Call `dbeaver_execute_sql` with only that approval ID. Replays, expired approvals, changed SQL, and changed connections are rejected.
6. Read a small preview with `dbeaver_get_last_result`, then page through stored rows with `dbeaver_fetch_result`.

Execution stores at most 1,000 rows per query, returns at most 20 rows in the preview, serves pages of at most 200 rows, caps individual string cells in previews/pages at 4,096 characters, and keeps the latest 50 operator executions in memory. `dbeaver_get_last_queries` reports only queries run through this operator bridge, not DBeaver's complete Query Manager history.

Execution results are currently returned through MCP for analysis and paging. They are not mirrored into DBeaver's native SQL result grid yet.

Editor-scoped execution reuses the editor execution context when available, so manual transaction mode can be preserved. `dbeaver_commit` and `dbeaver_rollback` show a separate native confirmation dialog. Use read-only database credentials when an agent must never modify the database because UI approval is not a database authorization boundary.

Samples and observations mask sensitive-looking fields by default. Passwords, tokens, card numbers, and national identifiers remain masked even when ordinary masking is disabled.

Table profiling defaults to `mode=quick`, which computes statistics from a bounded sample. `mode=full` requires `allow_full_scan=true` because it can scan the entire table once per column.

`dbeaver_simulate_change` accepts one `INSERT`, `UPDATE`, `DELETE`, or `MERGE`, shows a native DBeaver confirmation dialog, runs it in an isolated execution context, and rolls it back without committing. It still requires explicit acknowledgement flags. Rollback cannot undo sequence increments, notifications, external service calls, files, jobs, or autonomous transactions.

`database` and `schema` parameters on editor opening are currently preserved as requested context for the operator response. Actual catalog/schema selection remains controlled by the DBeaver connection and editor defaults.

## Provider SPI

An additive bundle can contribute tools without modifying this plugin:

```xml
<extension point="org.jkiss.dbeaver.mcp.toolProvider">
    <provider class="com.example.dbeaver.mcp.ExampleProvider" priority="200"/>
</extension>
```

The provider implements:

```java
public interface DBeaverMcpToolProvider {
    String id();
    default int priority() { return 100; }
    void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) throws Exception;
}
```

Long-running provider work should be submitted through `context.jobs()`. Jobs are bounded to the latest 100 entries, expose status/result/cancellation through `dbeaver_job`, and are cancelled when the MCP server stops.

## Policy scopes

Tool execution can be restricted with a Java property or environment variable:

```text
-Ddbeaver.mcp.scopes=observe,query,workspace,ui
DBEAVER_MCP_SCOPES=observe,query,workspace,ui
```

Supported scopes are:

```text
observe, query, data_write, schema_write, transfer,
task, test, admin, workspace, ui
```

The default is `all` to preserve existing behavior. Tools blocked by policy are omitted from `tools/list` and are rejected if invoked indirectly through a compact facade. Compact facades themselves stay discoverable and enforce the forwarded action's scope.

## Configuration

Use Java system properties in `dbeaver.ini`, or matching environment variables before launching DBeaver:

| Java property | Environment variable | Default |
|---|---|---|
| `dbeaver.mcp.enabled` | `DBEAVER_MCP_ENABLED` | `true` |
| `dbeaver.mcp.port` | `DBEAVER_MCP_PORT` | `3846` |
| `dbeaver.mcp.authToken` | `DBEAVER_MCP_AUTH_TOKEN` | empty |
| `dbeaver.mcp.transferRoot` | `DBEAVER_MCP_TRANSFER_ROOT` | `~/DBeaverData` |

Example `dbeaver.ini` entries after `-vmargs`:

```text
-Ddbeaver.mcp.port=3846
-Ddbeaver.mcp.authToken=replace-with-a-local-secret
```

When authentication is enabled, the LCA bridge must use the same value in `DBEAVER_DESKTOP_AUTH_TOKEN`.

Java system properties and environment variables override values saved in the preference page. Overridden fields are shown as read-only in the UI.

The MCP-specific log is kept in memory and written to the plugin state directory as `mcp.log`. The log viewer refreshes automatically and supports clear, copy, and open-file actions. Request payloads and SQL text are not written to this log.

## Focused build

The DBeaver checkout expects a sibling `dbeaver-common` checkout. With the shared dependencies already installed, compile the plugin and its internal dependencies:

```bash
mvn -f plugins/pom.xml -Pdesktop \
  -pl org.jkiss.dbeaver.model,org.jkiss.dbeaver.registry,org.jkiss.dbeaver.ui,org.jkiss.dbeaver.ui.navigator,org.jkiss.dbeaver.ui.editors.base,org.jkiss.dbeaver.ui.editors.data,org.jkiss.dbeaver.ui.editors.sql,org.jkiss.dbeaver.mcp \
  -am package -DskipTests
```

Standalone parser, safety, policy, job, CSV, and transfer-path tests live under `test/org/jkiss/dbeaver/mcp`.
