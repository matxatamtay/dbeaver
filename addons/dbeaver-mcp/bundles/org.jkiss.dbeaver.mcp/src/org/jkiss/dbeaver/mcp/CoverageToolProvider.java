/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CoverageToolProvider implements DBeaverMcpToolProvider {
   private final DBeaverWorkbenchService workbench = new DBeaverWorkbenchService();
   private final DBeaverQualityService quality;
   private DBeaverMcpContext context;

   CoverageToolProvider(McpToolRegistry registry) {
      this.quality = new DBeaverQualityService(registry, this.workbench);
   }

   @Override
   public String id() {
      return "coverage-expansion";
   }

   @Override
   public int priority() {
      return 60;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      this.context = context;
      registrar.register(tool(
         "dbeaver_workbench",
         "Inspect and safely operate DBeaver workbench parts, commands, Eclipse jobs, perspectives, views, and notifications.",
         List.of(
            "discover", "state", "list_editors", "list_views", "list_perspectives", "activate_part", "save_editor", "close_editor",
            "open_view", "hide_view", "switch_perspective", "list_commands", "execute_command", "list_jobs", "cancel_job",
            "list_notification_types", "get_notification_settings", "set_notification_settings", "send_test_notification"
         ),
         this::executeWorkbench
      ));
      registrar.register(tool(
         "dbeaver_quality",
         "Run cross-environment health, regression, schema-contract, anomaly, diagnostics, audit, and sanitized support workflows.",
         List.of(
            "discover", "connection_health", "connection_matrix", "environment_diff", "query_regression", "schema_contract",
            "anomaly_scan", "diagnostics", "support_bundle", "audit_log", "audit_metrics", "clear_audit"
         ),
         this::executeQuality
      ));
   }

   private JsonObject executeWorkbench(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      if (action.equals("discover")) return discovery("dbeaver_workbench", workbenchActions());
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      require(DBeaverMcpScope.UI);
      if (Set.of("state", "list_editors", "list_views", "list_perspectives", "list_commands", "list_jobs", "list_notification_types", "get_notification_settings").contains(action)) {
         require(DBeaverMcpScope.OBSERVE);
      }
      if (Set.of("save_editor", "set_notification_settings").contains(action) || (action.equals("close_editor") && McpJson.getBoolean(payload, "save", false))) {
         require(DBeaverMcpScope.WORKSPACE);
      }
      if (action.equals("save_editor") || (action.equals("close_editor") && McpJson.getBoolean(payload, "save", false))) {
         require(DBeaverMcpScope.DATA_WRITE);
      }
      if (Set.of("cancel_job").contains(action)) require(DBeaverMcpScope.ADMIN);
      if (action.equals("execute_command") && McpJson.getBoolean(payload, "allow_unsafe_command", false)) require(DBeaverMcpScope.ADMIN);
      return this.workbench.execute(action, payload);
   }

   private JsonObject executeQuality(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      if (action.equals("discover")) return discovery("dbeaver_quality", qualityActions());
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      require(DBeaverMcpScope.TEST);
      if (Set.of("connection_health", "connection_matrix", "audit_log", "audit_metrics", "diagnostics").contains(action)) {
         require(DBeaverMcpScope.OBSERVE);
      }
      if (Set.of("environment_diff", "query_regression", "schema_contract", "anomaly_scan").contains(action)) {
         require(DBeaverMcpScope.QUERY);
      }
      if (action.equals("anomaly_scan") && McpJson.getBoolean(payload, "include_postgres_admin", true)) require(DBeaverMcpScope.ADMIN);
      if (action.equals("support_bundle")) require(DBeaverMcpScope.OBSERVE, DBeaverMcpScope.TRANSFER, DBeaverMcpScope.WORKSPACE);
      if (action.equals("clear_audit")) require(DBeaverMcpScope.WORKSPACE);
      return this.quality.execute(action, payload, this.context.jobs());
   }

   private void require(DBeaverMcpScope... scopes) throws McpRequestException {
      Set<DBeaverMcpScope> required = Set.of(scopes);
      if (!this.context.policy().allows(required)) {
         throw new McpRequestException(-32001, "MCP policy does not allow scopes: " + required.stream().map(DBeaverMcpScope::id).sorted().toList());
      }
   }

   private static DBeaverMcpToolDefinition tool(String name, String description, List<String> actions, DBeaverMcpToolDefinition.Handler handler) {
      JsonObject action = McpJson.stringProperty("Action to execute. Use discover for action-specific contracts and safety semantics.");
      JsonArray values = new JsonArray();
      actions.forEach(values::add);
      action.add("enum", values);
      return new DBeaverMcpToolDefinition(
         name,
         description,
         McpJson.objectSchema(Map.of(
            "action", action,
            "arguments", McpJson.objectProperty("Action-specific arguments.")
         ), List.of("action")),
         Set.of(),
         false,
         false,
         false,
         handler
      );
   }

   private static JsonObject discovery(String facade, Map<String, String> actions) {
      JsonArray items = new JsonArray();
      actions.forEach((name, contract) -> {
         JsonObject item = new JsonObject();
         item.addProperty("action", name);
         item.addProperty("arguments", contract);
         items.add(item);
      });
      JsonObject result = new JsonObject();
      result.addProperty("facade", facade);
      result.addProperty("count", items.size());
      result.add("actions", items);
      return result;
   }

   private static Map<String, String> workbenchActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("state", "no arguments; active perspective/part and open part counts");
      result.put("list_editors", "no arguments; session-local part_id, title, type, dirty/active state; no document content");
      result.put("list_views", "no arguments; session-local part_id, view id/title/active state");
      result.put("list_perspectives", "no arguments");
      result.put("activate_part", "part_id");
      result.put("save_editor", "part_id, confirm=true; requires data_write because generic editors may persist database changes");
      result.put("close_editor", "part_id, save=false, confirm=true; save=true may invoke editor-native save behavior");
      result.put("open_view", "view_id from Eclipse view registry");
      result.put("hide_view", "part_id for an open view");
      result.put("switch_perspective", "perspective_id, confirm=true");
      result.put("list_commands", "optional search, limit<=1000; marks navigation-only safe commands");
      result.put("execute_command", "command_id, optional primitive parameters<=20; outside navigation allowlist requires allow_unsafe_command=true, admin scope, confirm=true");
      result.put("list_jobs", "optional search, limit<=500; Eclipse background jobs");
      result.put("cancel_job", "job_id, confirm=true; partial work may remain");
      result.put("list_notification_types", "optional include_hidden=false");
      result.put("get_notification_settings", "notification_id");
      result.put("set_notification_settings", "notification_id, optional show_popup/play_sound, confirm=true");
      result.put("send_test_notification", "optional title<=200, message<=2000, error=false");
      return result;
   }

   private static Map<String, String> qualityActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("connection_health", "connection, optional project, auto_connect=false; excludes credentials/user/url/properties");
      result.put("connection_matrix", "optional connections<=20 and project; excludes credentials/user/url/properties");
      result.put("environment_diff", "left/right connection and projects; optional compare_schema with schema comparison options");
      result.put("query_regression", "read-only sql, optional connections<=20/project/max_rows<=200/timeout<=300/mask_sensitive/include_rows; returns job_id");
      result.put("schema_contract", "contracts<=100 with object selectors and JSON-pointer assertions");
      result.put("anomaly_scan", "optional connections<=20/project/include_security/include_postgres_admin/max_security_objects<=2000; returns job_id");
      result.put("diagnostics", "optional project/connections/job_limit; sanitized live summary");
      result.put("support_bundle", "path under transfer root ending .json, confirm=true, overwrite=false, optional audit_limit; excludes credentials/SQL/arguments/results and raw MCP logs");
      result.put("audit_log", "optional limit<=1000; metadata only");
      result.put("audit_metrics", "no arguments; calls/success/fail/average latency by tool");
      result.put("clear_audit", "confirm=true; clears bounded in-memory metadata and counters");
      return result;
   }
}
