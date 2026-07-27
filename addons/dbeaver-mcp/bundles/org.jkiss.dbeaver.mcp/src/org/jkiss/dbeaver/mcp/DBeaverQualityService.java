/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.registry.DataSourceRegistry;

final class DBeaverQualityService {
   private static final int MAX_CONNECTIONS = 20;
   private static final int MAX_CONTRACTS = 100;
   private final McpToolRegistry registry;
   private final DBeaverConnectionService connections = new DBeaverConnectionService();
   private final DBeaverWorkbenchService workbench;

   DBeaverQualityService(McpToolRegistry registry, DBeaverWorkbenchService workbench) {
      this.registry = registry;
      this.workbench = workbench;
   }

   JsonObject execute(String action, JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      return switch (action) {
         case "connection_health" -> connectionHealth(arguments);
         case "connection_matrix" -> connectionMatrix(arguments);
         case "environment_diff" -> environmentDiff(arguments);
         case "query_regression" -> submitQueryRegression(arguments, jobs);
         case "schema_contract" -> schemaContract(arguments);
         case "anomaly_scan" -> submitAnomalyScan(arguments, jobs);
         case "diagnostics" -> diagnostics(arguments, jobs);
         case "support_bundle" -> supportBundle(arguments, jobs);
         case "audit_log" -> this.registry.audit().list(McpJson.getInt(arguments, "limit", 100, 1, 1000));
         case "audit_metrics" -> this.registry.audit().metrics();
         case "clear_audit" -> clearAudit(arguments);
         default -> throw new IllegalArgumentException("Unknown quality action: " + action);
      };
   }

   private JsonObject connectionHealth(JsonObject arguments) throws Exception {
      String connectionName = McpJson.requiredString(arguments, "connection");
      String project = McpJson.getString(arguments, "project", "");
      boolean autoConnect = McpJson.getBoolean(arguments, "auto_connect", false);
      DBPDataSourceContainer container = DBeaverConnectionService.findConnection(connectionName, project);
      long startedAt = System.nanoTime();
      String error = "";
      if (autoConnect && !container.isConnected()) {
         try {
            this.connections.resolve(connectionName, project, true);
         } catch (Exception e) {
            error = e.getClass().getSimpleName();
         }
      }
      JsonObject result = safeConnection(container);
      result.addProperty("healthy", container.isConnected() && (container.getConnectionError() == null || container.getConnectionError().isBlank()));
      result.addProperty("auto_connect_requested", autoConnect);
      result.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000.0);
      if (!error.isBlank()) result.addProperty("health_error", error);
      if (container.getDataSource() != null) {
         result.addProperty("database_product", container.getDataSource().getInfo().getDatabaseProductName());
         result.addProperty("database_version", container.getDataSource().getInfo().getDatabaseProductVersion());
         result.addProperty("driver_name", container.getDataSource().getInfo().getDriverName());
         result.addProperty("driver_version", container.getDataSource().getInfo().getDriverVersion());
      }
      return result;
   }

   private JsonObject connectionMatrix(JsonObject arguments) {
      List<DBPDataSourceContainer> selected = selectConnections(arguments);
      JsonArray items = new JsonArray();
      int healthy = 0;
      for (DBPDataSourceContainer container : selected) {
         JsonObject item = safeConnection(container);
         boolean ok = container.isConnected() && (container.getConnectionError() == null || container.getConnectionError().isBlank());
         item.addProperty("healthy", ok);
         if (ok) healthy++;
         items.add(item);
      }
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.addProperty("healthy_count", healthy);
      result.addProperty("unhealthy_count", items.size() - healthy);
      result.add("connections", items);
      result.addProperty("credential_fields_included", false);
      return result;
   }

   private JsonObject environmentDiff(JsonObject arguments) throws Exception {
      DBPDataSourceContainer left = DBeaverConnectionService.findConnection(
         McpJson.requiredString(arguments, "left_connection"), McpJson.getString(arguments, "left_project", "")
      );
      DBPDataSourceContainer right = DBeaverConnectionService.findConnection(
         McpJson.requiredString(arguments, "right_connection"), McpJson.getString(arguments, "right_project", "")
      );
      JsonObject leftPayload = safeConnection(left);
      JsonObject rightPayload = safeConnection(right);
      JsonArray differences = shallowDifferences(leftPayload, rightPayload);
      JsonObject result = new JsonObject();
      result.add("left", leftPayload);
      result.add("right", rightPayload);
      result.addProperty("configuration_equal", differences.isEmpty());
      result.addProperty("configuration_difference_count", differences.size());
      result.add("configuration_differences", differences);
      if (McpJson.getBoolean(arguments, "compare_schema", false)) {
         JsonObject schemaArguments = new JsonObject();
         schemaArguments.addProperty("left_connection", left.getId());
         schemaArguments.addProperty("left_project", left.getProject().getName());
         schemaArguments.addProperty("right_connection", right.getId());
         schemaArguments.addProperty("right_project", right.getProject().getName());
         copy(arguments, schemaArguments, "left_schema", "right_schema", "include_ddl", "max_objects", "include_unchanged", "auto_connect");
         if (arguments.has("types")) schemaArguments.add("types", arguments.get("types").deepCopy());
         result.add("schema_comparison", this.registry.executeRaw("dbeaver_compare_schemas", schemaArguments));
      }
      return result;
   }

   private JsonObject submitQueryRegression(JsonObject arguments, DBeaverMcpJobManager jobs) {
      String sql = McpJson.requiredString(arguments, "sql");
      if (!SqlSafety.isReadOnly(sql)) throw new IllegalArgumentException("Query regression only accepts read-only SQL");
      List<DBPDataSourceContainer> selected = selectConnections(arguments);
      int maxRows = McpJson.getInt(arguments, "max_rows", 100, 1, 200);
      int timeout = McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300);
      boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
      String jobId = jobs.submit("coverage-expansion", "query-regression", true, context -> {
         JsonArray results = new JsonArray();
         Set<String> fingerprints = new LinkedHashSet<>();
         for (DBPDataSourceContainer container : selected) {
            context.checkCancelled();
            JsonObject item = new JsonObject();
            item.add("connection", safeConnection(container));
            long startedAt = System.nanoTime();
            try {
               JsonObject queryArguments = new JsonObject();
               queryArguments.addProperty("connection", container.getId());
               queryArguments.addProperty("project", container.getProject().getName());
               queryArguments.addProperty("sql", sql);
               queryArguments.addProperty("max_rows", maxRows);
               queryArguments.addProperty("timeout_seconds", timeout);
               JsonObject queryResult = this.registry.executeRaw("dbeaver_profile_query", queryArguments);
               JsonObject safeResult = SensitiveDataPolicy.maskQueryPayload(queryResult, maskSensitive);
               String fingerprint = fingerprint(normalizeQueryResult(safeResult));
               fingerprints.add(fingerprint);
               item.addProperty("success", true);
               item.addProperty("fingerprint", fingerprint);
               item.addProperty("row_count", safeResult.has("row_count") ? safeResult.get("row_count").getAsInt() : 0);
               item.addProperty("truncated", safeResult.has("truncated") && safeResult.get("truncated").getAsBoolean());
               item.add("columns", safeResult.has("columns") ? safeResult.get("columns").deepCopy() : new JsonArray());
               if (McpJson.getBoolean(arguments, "include_rows", false)) {
                  item.add("rows", boundedJson(safeResult.has("rows") ? safeResult.get("rows") : new JsonArray(), 65536));
               }
            } catch (Exception e) {
               item.addProperty("success", false);
               item.addProperty("error", McpJson.safeMessage(e));
            }
            item.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000.0);
            results.add(item);
         }
         JsonObject result = new JsonObject();
         result.addProperty("connection_count", selected.size());
         result.addProperty("consistent", fingerprints.size() <= 1 && successfulCount(results) == selected.size());
         result.addProperty("distinct_fingerprint_count", fingerprints.size());
         result.addProperty("successful_count", successfulCount(results));
         result.addProperty("failed_count", selected.size() - successfulCount(results));
         result.addProperty("mask_sensitive", maskSensitive);
         result.add("results", results);
         return result;
      });
      return jobPayload(jobId, "query-regression");
   }

   private JsonObject schemaContract(JsonObject arguments) throws Exception {
      List<JsonObject> contracts = DBeaverAssertionEngine.objectList(arguments, "contracts", MAX_CONTRACTS);
      if (contracts.isEmpty()) throw new IllegalArgumentException("contracts must contain at least one object contract");
      JsonArray reports = new JsonArray();
      int passed = 0;
      for (JsonObject contract : contracts) {
         JsonObject describeArguments = new JsonObject();
         for (String key : List.of("connection", "project", "object_id", "qualified_name", "name", "schema", "type", "auto_connect")) {
            if (contract.has(key)) describeArguments.add(key, contract.get(key).deepCopy());
         }
         JsonObject report = new JsonObject();
         report.addProperty("name", McpJson.getString(contract, "contract_name", McpJson.getString(contract, "qualified_name", McpJson.getString(contract, "name", "contract"))));
         try {
            JsonObject description = this.registry.executeRaw("dbeaver_describe_object", describeArguments);
            JsonObject assertions = DBeaverAssertionEngine.evaluate(description, DBeaverAssertionEngine.array(contract, "assertions"));
            boolean ok = assertions.get("passed").getAsBoolean();
            report.addProperty("passed", ok);
            report.add("assertions", boundedJson(assertions, 8192));
            report.add("object", boundedJson(description.has("object") ? description.get("object") : description, 8192));
            if (ok) passed++;
         } catch (Exception e) {
            report.addProperty("passed", false);
            report.addProperty("error", McpJson.safeMessage(e));
         }
         reports.add(report);
      }
      JsonObject result = new JsonObject();
      result.addProperty("passed", passed == contracts.size());
      result.addProperty("contract_count", contracts.size());
      result.addProperty("passed_count", passed);
      result.addProperty("failed_count", contracts.size() - passed);
      result.add("contracts", reports);
      return result;
   }

   private JsonObject submitAnomalyScan(JsonObject arguments, DBeaverMcpJobManager jobs) {
      List<DBPDataSourceContainer> selected = selectConnections(arguments);
      boolean includeSecurity = McpJson.getBoolean(arguments, "include_security", true);
      boolean includePostgresAdmin = McpJson.getBoolean(arguments, "include_postgres_admin", true);
      int maxSecurityObjects = McpJson.getInt(arguments, "max_security_objects", 500, 1, 2000);
      String jobId = jobs.submit("coverage-expansion", "anomaly-scan", true, context -> {
         JsonArray environments = new JsonArray();
         int riskCount = 0;
         for (DBPDataSourceContainer container : selected) {
            context.checkCancelled();
            JsonObject environment = new JsonObject();
            environment.add("connection", safeConnection(container));
            JsonArray risks = new JsonArray();
            if (!container.isConnected()) risks.add(risk("connection_offline", "medium", "Connection is offline."));
            if (container.getConnectionError() != null && !container.getConnectionError().isBlank()) {
               risks.add(risk("connection_error", "high", "The DBeaver connection currently reports an error; raw error text is excluded."));
            }
            if (includeSecurity && container.isConnected()) {
               try {
                  JsonObject securityArgs = selector(container);
                  securityArgs.addProperty("max_objects", maxSecurityObjects);
                  JsonObject security = this.registry.executeRaw("dbeaver_security_summary", securityArgs);
                  environment.add("security", boundedJson(security, 65536));
                  JsonArray securityRisks = security.getAsJsonArray("risks");
                  if (securityRisks != null) for (JsonElement item : securityRisks) risks.add(item.deepCopy());
               } catch (Exception e) {
                  environment.addProperty("security_error", McpJson.safeMessage(e));
               }
            }
            if (includePostgresAdmin && container.isConnected() && container.getDriver().getId().toLowerCase().contains("postgres")) {
               try {
                  JsonObject blocking = this.registry.executeRaw("dbeaver_admin", facadeArguments("blocking_tree", selector(container)));
                  environment.add("blocking", blocking);
                  int count = blocking.has("row_count") ? blocking.get("row_count").getAsInt() : 0;
                  if (count > 0) risks.add(risk("blocked_sessions", "high", count + " blocking-chain rows detected."));
               } catch (Exception e) {
                  environment.addProperty("blocking_error", McpJson.safeMessage(e));
               }
            }
            riskCount += risks.size();
            environment.addProperty("risk_count", risks.size());
            environment.add("risks", risks);
            environments.add(environment);
         }
         JsonObject result = new JsonObject();
         result.addProperty("connection_count", selected.size());
         result.addProperty("risk_count", riskCount);
         result.addProperty("passed", riskCount == 0);
         result.add("environments", environments);
         return result;
      });
      return jobPayload(jobId, "anomaly-scan");
   }

   private JsonObject diagnostics(JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      JsonObject result = new JsonObject();
      result.add("status", this.registry.executeRaw("dbeaver_status", new JsonObject()));
      result.add("connections", connectionMatrix(arguments));
      try {
         result.add("workbench", this.workbench.execute("state", new JsonObject()));
      } catch (Exception e) {
         result.addProperty("workbench_error", McpJson.safeMessage(e));
      }
      result.add("mcp_jobs", jobs.list(McpJson.getInt(arguments, "job_limit", 20, 1, 100)));
      result.add("audit_metrics", this.registry.audit().metrics());
      JsonObject tools = this.registry.listTools();
      result.addProperty("tool_count", tools.getAsJsonArray("tools").size());
      result.addProperty("mcp_log_path_available", McpLog.getLogPath() != null);
      result.addProperty("generated_at", Instant.now().toString());
      return result;
   }

   private JsonObject supportBundle(JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      Path output = new DBeaverTransferPathPolicy().resolveOutput(McpJson.requiredString(arguments, "path"));
      if (!output.getFileName().toString().toLowerCase().endsWith(".json")) throw new IllegalArgumentException("Support bundle path must end with .json");
      boolean overwrite = McpJson.getBoolean(arguments, "overwrite", false);
      if (Files.exists(output) && !overwrite) throw new IllegalArgumentException("Output exists; pass overwrite=true: " + output);
      if (!DBeaverNativeConfirmation.confirm("Export DBeaver MCP support bundle?", "Write a sanitized diagnostic JSON bundle to:\n" + output + "\n\nThe bundle excludes credentials, SQL, tool arguments, query rows, and raw results.")) {
         throw new IllegalStateException("Operation cancelled by the DBeaver user");
      }
      JsonObject bundle = diagnostics(arguments, jobs);
      bundle.add("audit_log", this.registry.audit().list(McpJson.getInt(arguments, "audit_limit", 100, 1, 1000)));
      bundle.addProperty("mcp_log_included", false);
      bundle.addProperty("mcp_log_exclusion_reason", "Request failure stack traces may contain database-supplied text and are excluded from sanitized bundles.");
      JsonObject privacy = new JsonObject();
      privacy.addProperty("credentials", "excluded");
      privacy.addProperty("connection_urls", "excluded");
      privacy.addProperty("user_names", "excluded");
      privacy.addProperty("sql_and_tool_arguments", "excluded");
      privacy.addProperty("query_rows_and_tool_results", "excluded");
      bundle.add("privacy", privacy);
      byte[] bytes = McpJson.GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("Sanitized support bundle exceeds the 4 MiB safety limit");
      Files.write(output, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      JsonObject result = new JsonObject();
      result.addProperty("written", true);
      result.addProperty("path", output.toString());
      result.addProperty("bytes", bytes.length);
      result.add("privacy", privacy);
      return result;
   }

   private JsonObject clearAudit(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      if (!DBeaverNativeConfirmation.confirm("Clear DBeaver MCP audit metadata?", "Clear the bounded in-memory MCP tool audit log and counters?")) {
         throw new IllegalStateException("Operation cancelled by the DBeaver user");
      }
      return this.registry.audit().clear();
   }

   private List<DBPDataSourceContainer> selectConnections(JsonObject arguments) {
      List<String> requested = McpJson.getStrings(arguments, "connections");
      String project = McpJson.getString(arguments, "project", "");
      List<DBPDataSourceContainer> result = new ArrayList<>();
      if (requested.isEmpty()) {
         for (DBPDataSourceContainer container : DataSourceRegistry.getAllDataSources()) {
            if (project.isBlank() || project.equals(container.getProject().getName())) result.add(container);
         }
      } else {
         if (requested.size() > MAX_CONNECTIONS) throw new IllegalArgumentException("At most " + MAX_CONNECTIONS + " connections are allowed");
         for (String name : requested) result.add(DBeaverConnectionService.findConnection(name, project));
      }
      if (result.size() > MAX_CONNECTIONS) result = new ArrayList<>(result.subList(0, MAX_CONNECTIONS));
      return List.copyOf(result);
   }

   private static JsonObject safeConnection(DBPDataSourceContainer container) {
      DBPConnectionConfiguration config = container.getConnectionConfiguration();
      JsonObject result = DBeaverConnectionService.connectionPayload(container);
      boolean hasConnectionError = result.has("connection_error");
      result.remove("connection_error");
      result.addProperty("has_connection_error", hasConnectionError);
      result.addProperty("provider_id", container.getDriver().getProviderId());
      result.addProperty("driver_full_id", container.getDriver().getFullId());
      result.addProperty("configuration_type", config.getConfigurationType().name());
      result.addProperty("connection_type", config.getConnectionType().getId());
      addNonSecret(result, "host", config.getHostName());
      addNonSecret(result, "port", config.getHostPort());
      addNonSecret(result, "server", config.getServerName());
      addNonSecret(result, "database", config.getDatabaseName());
      addNonSecret(result, "client_home_id", config.getClientHomeId());
      addNonSecret(result, "profile_source", config.getConfigProfileSource());
      addNonSecret(result, "profile_name", config.getConfigProfileName());
      result.addProperty("network_handler_count", config.getHandlers().stream().filter(item -> item.isEnabled()).count());
      result.addProperty("keep_alive_seconds", config.getKeepAliveInterval());
      result.addProperty("close_idle_connection", config.isCloseIdleConnection());
      result.addProperty("credential_fields_included", false);
      return result;
   }

   private static JsonElement boundedJson(JsonElement value, int maximumChars) {
      String json = McpJson.GSON.toJson(value);
      if (json.length() <= maximumChars) return value.deepCopy();
      JsonObject bounded = new JsonObject();
      bounded.addProperty("truncated", true);
      bounded.addProperty("original_chars", json.length());
      bounded.addProperty("preview", json.substring(0, Math.min(65536, json.length())));
      return bounded;
   }

   private static JsonObject normalizeQueryResult(JsonObject result) {
      JsonObject copy = new JsonObject();
      for (String key : List.of("columns", "rows", "row_count", "truncated", "has_result_set", "update_count")) {
         if (result.has(key)) copy.add(key, result.get(key).deepCopy());
      }
      return copy;
   }

   private static String fingerprint(JsonElement value) throws Exception {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(McpJson.GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
   }

   private static int successfulCount(JsonArray results) {
      int count = 0;
      for (JsonElement item : results) if (item.getAsJsonObject().get("success").getAsBoolean()) count++;
      return count;
   }

   private static JsonArray shallowDifferences(JsonObject left, JsonObject right) {
      Set<String> keys = new LinkedHashSet<>();
      keys.addAll(left.keySet());
      keys.addAll(right.keySet());
      JsonArray differences = new JsonArray();
      for (String key : keys.stream().sorted().toList()) {
         JsonElement a = left.get(key);
         JsonElement b = right.get(key);
         if (a == null || b == null || !a.equals(b)) {
            JsonObject difference = new JsonObject();
            difference.addProperty("field", key);
            if (a != null) difference.add("left", a.deepCopy());
            if (b != null) difference.add("right", b.deepCopy());
            differences.add(difference);
         }
      }
      return differences;
   }

   private static JsonObject selector(DBPDataSourceContainer container) {
      JsonObject result = new JsonObject();
      result.addProperty("connection", container.getId());
      result.addProperty("project", container.getProject().getName());
      return result;
   }

   private static JsonObject facadeArguments(String action, JsonObject arguments) {
      JsonObject result = new JsonObject();
      result.addProperty("action", action);
      result.add("arguments", arguments);
      return result;
   }

   private static JsonObject risk(String kind, String severity, String evidence) {
      JsonObject result = new JsonObject();
      result.addProperty("kind", kind);
      result.addProperty("severity", severity);
      result.addProperty("evidence", evidence);
      return result;
   }

   private static JsonObject jobPayload(String jobId, String type) {
      JsonObject result = new JsonObject();
      result.addProperty("job_id", jobId);
      result.addProperty("type", type);
      result.addProperty("submitted", true);
      return result;
   }

   private static void copy(JsonObject source, JsonObject target, String... keys) {
      for (String key : keys) if (source.has(key)) target.add(key, source.get(key).deepCopy());
   }

   private static void addNonSecret(JsonObject target, String name, String value) {
      if (value != null && !value.isBlank()) target.addProperty(name, McpJson.truncate(value));
   }
}
