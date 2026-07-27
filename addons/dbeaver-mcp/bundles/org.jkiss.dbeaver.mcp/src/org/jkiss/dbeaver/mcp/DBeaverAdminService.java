/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSession;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionManager;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

final class DBeaverAdminService {
   private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}");
   private static final Set<String> OPERATIONS = Set.of("vacuum", "analyze", "reindex", "create_schema", "install_extension");
   private final DBeaverConnectionService connections = new DBeaverConnectionService();
   private final DBeaverSqlService sql = new DBeaverSqlService();
   private final DBeaverObjectService objects = new DBeaverObjectService(this.connections);

   JsonObject execute(String action, JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      return switch (action) {
         case "list_sessions" -> listSessions(arguments);
         case "list_locks", "blocking_tree" -> queryPostgres(arguments, LOCKS_SQL, McpJson.getInt(arguments, "limit", 200, 1, 500));
         case "postgres_overview" -> queryPostgres(arguments, OVERVIEW_SQL, 20);
         case "table_sizes" -> queryPostgres(arguments, TABLE_SIZES_SQL, McpJson.getInt(arguments, "limit", 100, 1, 200));
         case "extensions" -> queryPostgres(arguments, EXTENSIONS_SQL, McpJson.getInt(arguments, "limit", 200, 1, 500));
         case "cancel_session" -> alterSession(arguments, true);
         case "terminate_session" -> alterSession(arguments, false);
         case "plan_operation" -> plan(arguments);
         case "run_operation" -> run(arguments, jobs);
         default -> throw new IllegalArgumentException("Unknown admin action: " + action);
      };
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   private JsonObject listSessions(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBAServerSessionManager manager = DBUtils.getAdapter(DBAServerSessionManager.class, connection.dataSource());
      if (manager == null) throw new IllegalStateException("This database driver does not expose a DBeaver session manager");
      int limit = McpJson.getInt(arguments, "limit", 200, 1, 500);
      Map<String, Object> options = new LinkedHashMap<>();
      options.put("showIdle", McpJson.getBoolean(arguments, "show_idle", false));
      JsonArray items = new JsonArray();
      try (DBCSession session = DBUtils.openUtilSession(new VoidProgressMonitor(), connection.dataSource(), "MCP list database sessions")) {
         Collection<? extends DBAServerSession> sessions = manager.getSessions(session, options);
         for (DBAServerSession serverSession : sessions) {
            if (items.size() >= limit) break;
            JsonObject item = new JsonObject();
            item.addProperty("id", serverSession.getSessionId());
            item.addProperty("type", serverSession.getClass().getSimpleName());
            if (serverSession.getActiveQueryId() != null) item.addProperty("active_query_id", String.valueOf(serverSession.getActiveQueryId()));
            if (serverSession.getActiveQuery() != null) item.addProperty("active_query", McpJson.truncate(serverSession.getActiveQuery()));
            items.add(item);
         }
      }
      JsonObject result = new JsonObject();
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      result.addProperty("count", items.size());
      result.add("sessions", items);
      return SensitiveDataPolicy.maskQueryPayload(result, true);
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   private JsonObject alterSession(JsonObject arguments, boolean cancel) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      String sessionId = sessionId(arguments);
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBAServerSessionManager manager = DBUtils.getAdapter(DBAServerSessionManager.class, connection.dataSource());
      if (manager == null) throw new IllegalStateException("This database driver does not expose a DBeaver session manager");
      String verb = cancel ? "cancel the active query for" : "terminate";
      if (!DBeaverNativeConfirmation.confirm((cancel ? "Cancel query" : "Terminate session") + "?",
         "Connection: " + connection.container().getName() + "\nSession: " + sessionId + "\n\nDo you want to " + verb + " this database session?")) {
         throw new IllegalStateException("Operation cancelled by the DBeaver user");
      }
      Map<String, Object> options = new LinkedHashMap<>(manager.getTerminateOptions());
      options.put("isQueryCancel", cancel);
      try (DBCSession session = DBUtils.openUtilSession(new VoidProgressMonitor(), connection.dataSource(), "MCP alter database session")) {
         manager.alterSession(session, sessionId, options);
      }
      JsonObject result = new JsonObject();
      result.addProperty(cancel ? "cancel_requested" : "termination_requested", true);
      result.addProperty("session_id", sessionId);
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      return result;
   }

   private JsonObject plan(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      requirePostgres(connection);
      String operation = operation(arguments);
      String statement = operationSql(connection, operation, arguments);
      JsonObject result = new JsonObject();
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      result.addProperty("operation", operation);
      result.addProperty("sql", statement);
      result.addProperty("writes_database", true);
      result.addProperty("requires_confirmation", true);
      return result;
   }

   private JsonObject run(JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      requirePostgres(connection);
      String operation = operation(arguments);
      String statement = operationSql(connection, operation, arguments);
      String summary = "DBeaver MCP is requesting a PostgreSQL administration operation.\n\nConnection: "
         + connection.container().getProject().getName() + "/" + connection.container().getName()
         + "\nOperation: " + operation + "\n\nReview the complete SQL below before approving.";
      if (!DBeaverNativeConfirmation.confirmSql("Run PostgreSQL administration operation?", summary, statement, "Run")) {
         throw new IllegalStateException("Operation cancelled by the DBeaver user");
      }
      String jobId = jobs.submit("desktop-workflows", "postgres-admin-" + operation, true, context -> {
         context.checkCancelled();
         JsonObject result = this.sql.executeApproved(connection, null, statement, 50,
            McpJson.getInt(arguments, "timeout_seconds", 300, 1, 3600), false);
         context.checkCancelled();
         result.addProperty("operation", operation);
         return result;
      });
      JsonObject result = new JsonObject();
      result.addProperty("job_id", jobId);
      result.addProperty("operation", operation);
      result.addProperty("state", "queued");
      result.addProperty("status_tool", "dbeaver_job");
      return result;
   }

   private JsonObject queryPostgres(JsonObject arguments, String statement, int limit) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      requirePostgres(connection);
      JsonObject result = this.sql.query(connection, statement, limit, McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300));
      return SensitiveDataPolicy.maskQueryPayload(result, true);
   }

   private String operationSql(DBeaverConnectionService.ResolvedConnection connection, String operation, JsonObject arguments) throws Exception {
      return switch (operation) {
         case "vacuum" -> "VACUUM " + objectName(connection, arguments);
         case "analyze" -> "ANALYZE " + objectName(connection, arguments);
         case "reindex" -> "REINDEX TABLE " + objectName(connection, arguments);
         case "create_schema" -> "CREATE SCHEMA " + quoteIdentifier(connection, requiredIdentifier(arguments, "name"));
         case "install_extension" -> "CREATE EXTENSION IF NOT EXISTS " + quoteIdentifier(connection, requiredIdentifier(arguments, "name"));
         default -> throw new IllegalArgumentException("Unsupported PostgreSQL administration operation: " + operation);
      };
   }

   private String objectName(DBeaverConnectionService.ResolvedConnection connection, JsonObject arguments) throws Exception {
      DBSObject object = this.objects.resolve(connection, arguments);
      return DBeaverObjectService.dmlName(object);
   }

   private static String quoteIdentifier(DBeaverConnectionService.ResolvedConnection connection, String identifier) {
      return DBUtils.getQuotedIdentifier(connection.dataSource(), identifier);
   }

   private static String requiredIdentifier(JsonObject arguments, String key) {
      String value = McpJson.requiredString(arguments, key);
      if (!SIMPLE_IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException(key + " must be a simple PostgreSQL identifier");
      return value;
   }

   private static String operation(JsonObject arguments) {
      String operation = McpJson.requiredString(arguments, "operation").toLowerCase(Locale.ENGLISH);
      if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("operation must be one of: " + String.join(", ", OPERATIONS.stream().sorted().toList()));
      return operation;
   }

   private static String sessionId(JsonObject arguments) {
      String value = McpJson.requiredString(arguments, "session_id");
      if (!value.matches("[0-9]{1,20}")) throw new IllegalArgumentException("session_id must be numeric");
      return value;
   }

   private static void requirePostgres(DBeaverConnectionService.ResolvedConnection connection) {
      String driver = connection.container().getDriver().getFullId().toLowerCase(Locale.ENGLISH);
      String name = connection.container().getDriver().getName().toLowerCase(Locale.ENGLISH);
      if (!driver.contains("postgres") && !name.contains("postgres")) throw new IllegalArgumentException("This action currently supports PostgreSQL connections only");
   }

   private static final String OVERVIEW_SQL = """
      SELECT current_database() AS database,
             current_user AS current_user,
             version() AS version,
             pg_database_size(current_database()) AS database_bytes,
             pg_size_pretty(pg_database_size(current_database())) AS database_size,
             current_setting('server_version') AS server_version,
             pg_is_in_recovery() AS in_recovery
      """;

   private static final String TABLE_SIZES_SQL = """
      SELECT schemaname AS schema_name,
             relname AS table_name,
             pg_total_relation_size(relid) AS total_bytes,
             pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
             pg_relation_size(relid) AS table_bytes,
             pg_indexes_size(relid) AS index_bytes
        FROM pg_catalog.pg_statio_user_tables
       ORDER BY pg_total_relation_size(relid) DESC
      """;

   private static final String EXTENSIONS_SQL = """
      SELECT e.extname AS name,
             e.extversion AS version,
             n.nspname AS schema_name,
             a.default_version,
             a.comment
        FROM pg_catalog.pg_extension e
        JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
        LEFT JOIN pg_catalog.pg_available_extensions a ON a.name = e.extname
       ORDER BY e.extname
      """;

   private static final String LOCKS_SQL = """
      SELECT blocked.pid AS blocked_pid,
             blocked.usename AS blocked_user,
             blocking.pid AS blocking_pid,
             blocking.usename AS blocking_user,
             blocked.wait_event_type,
             blocked.wait_event,
             blocked.query AS blocked_query,
             blocking.query AS blocking_query
        FROM pg_catalog.pg_stat_activity blocked
        JOIN LATERAL unnest(pg_catalog.pg_blocking_pids(blocked.pid)) AS blocker(pid) ON true
        JOIN pg_catalog.pg_stat_activity blocking ON blocking.pid = blocker.pid
       ORDER BY blocked.pid, blocking.pid
      """;
}
