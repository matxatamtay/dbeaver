# DBeaver Desktop MCP

This plugin starts a loopback-only MCP server inside DBeaver Desktop after the workbench starts.

A control panel is available at **Window → Preferences → User Interface → MCP Server**. It provides live Start/Stop controls, automatic-start configuration, port and bearer-token settings, current status, and an MCP-specific log viewer.

## Endpoints

- MCP: `http://127.0.0.1:3846/mcp`
- Health: `http://127.0.0.1:3846/healthz`

## Tools

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

## Configuration

Use Java system properties in `dbeaver.ini`, or matching environment variables before launching DBeaver:

| Java property | Environment variable | Default |
|---|---|---|
| `dbeaver.mcp.enabled` | `DBEAVER_MCP_ENABLED` | `true` |
| `dbeaver.mcp.port` | `DBEAVER_MCP_PORT` | `3846` |
| `dbeaver.mcp.authToken` | `DBEAVER_MCP_AUTH_TOKEN` | empty |

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
  -pl org.jkiss.dbeaver.model,org.jkiss.dbeaver.registry,org.jkiss.dbeaver.ui,org.jkiss.dbeaver.ui.editors.base,org.jkiss.dbeaver.ui.editors.sql,org.jkiss.dbeaver.mcp \
  -am package -DskipTests
```

Standalone parser and safety tests live under `test/org/jkiss/dbeaver/mcp`.
