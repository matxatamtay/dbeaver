# DBeaver Desktop MCP

This plugin starts a loopback-only MCP server inside DBeaver Desktop after the workbench starts.

A control panel is available at **Window → Preferences → User Interface → MCP Server**. It provides live Start/Stop controls, automatic-start configuration, port and bearer-token settings, current status, and an MCP-specific log viewer.

## Endpoints

- MCP: `http://127.0.0.1:3846/mcp`
- Health: `http://127.0.0.1:3846/healthz`

## Tools

### Workspace and SQL

- `dbeaver_status`
- `dbeaver_list_connections`
- `dbeaver_execute_sql`
- `dbeaver_profile_query`
- `dbeaver_explain_query`

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

## Safety model

`dbeaver_execute_sql` limits returned rows and applies a statement timeout. Statements that look mutating require `allow_write=true`. This check is a safety guard, not a database authorization boundary. Use read-only database credentials when an agent must not modify the database.

Samples and observations mask sensitive-looking fields by default. Passwords, tokens, card numbers, and national identifiers remain masked even when ordinary masking is disabled.

Table profiling defaults to `mode=quick`, which computes statistics from a bounded sample. `mode=full` requires `allow_full_scan=true` because it can scan the entire table once per column.

`dbeaver_simulate_change` accepts one `INSERT`, `UPDATE`, `DELETE`, or `MERGE`, runs it in an isolated execution context, and rolls it back without committing. It requires explicit acknowledgement flags. Rollback cannot undo sequence increments, notifications, external service calls, files, jobs, or autonomous transactions.

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
  -pl org.jkiss.dbeaver.model,org.jkiss.dbeaver.registry,org.jkiss.dbeaver.mcp \
  package -DskipTests
```

Standalone parser and safety tests live under `test/org/jkiss/dbeaver/mcp`.
