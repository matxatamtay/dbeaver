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

final class Phase3ToolProvider implements DBeaverMcpToolProvider {
   private final DBeaverTaskService tasks = new DBeaverTaskService();
   private final DBeaverProjectService projects = new DBeaverProjectService();
   private final DBeaverEnvironmentService environment = new DBeaverEnvironmentService();
   private final DBeaverVisualService visual = new DBeaverVisualService();
   private final DBeaverAdminService admin = new DBeaverAdminService();
   private DBeaverMcpContext context;

   @Override
   public String id() {
      return "desktop-workflows";
   }

   @Override
   public int priority() {
      return 40;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      this.context = context;
      registrar.register(tool("dbeaver_task", "Inspect, configure, schedule, run, and review native DBeaver tasks.",
         List.of("discover", "list_types", "list", "describe", "create", "update", "delete", "run", "cancel_running", "history", "read_log", "schedule", "unschedule", "scheduled"), this::executeTask));
      registrar.register(tool("dbeaver_project", "Operate DBeaver projects and bounded SQL script files inside project Scripts folders.",
         List.of("discover", "list", "create", "rename", "delete", "refresh", "list_scripts", "read_script", "write_script", "delete_script"), this::executeProject));
      registrar.register(tool("dbeaver_environment", "Inspect DBeaver drivers and read or safely update application preferences.",
         List.of("discover", "list_drivers", "describe_driver", "validate_connection_driver", "get_preference", "set_preference", "reset_preference"), this::executeEnvironment));
      registrar.register(tool("dbeaver_visual", "Open, inspect, refresh, lay out, save, and export native DBeaver ER diagrams.",
         List.of("discover", "open_erd", "list_editors", "active_editor", "state", "refresh", "auto_layout", "save", "export"), this::executeVisual));
      registrar.register(tool("dbeaver_admin", "Monitor sessions and locks and run explicitly confirmed PostgreSQL administration operations.",
         List.of("discover", "list_sessions", "list_locks", "blocking_tree", "postgres_overview", "table_sizes", "extensions", "cancel_session", "terminate_session", "plan_operation", "run_operation"), this::executeAdmin));
   }

   private JsonObject executeTask(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      if (action.equals("discover")) return discovery("dbeaver_task", taskActions());
      require(DBeaverMcpScope.TASK);
      if (Set.of("list_types", "list", "describe", "history", "read_log", "scheduled").contains(action)) {
         require(DBeaverMcpScope.OBSERVE);
      }
      return this.tasks.execute(action, payload, this.context.jobs());
   }

   private JsonObject executeProject(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      if (action.equals("discover")) return discovery("dbeaver_project", projectActions());
      require(DBeaverMcpScope.WORKSPACE);
      if (Set.of("list", "list_scripts", "read_script").contains(action)) require(DBeaverMcpScope.OBSERVE);
      return this.projects.execute(action, payload);
   }

   private JsonObject executeEnvironment(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      if (action.equals("discover")) return discovery("dbeaver_environment", environmentActions());
      if (Set.of("set_preference", "reset_preference").contains(action)) require(DBeaverMcpScope.WORKSPACE);
      else require(DBeaverMcpScope.OBSERVE);
      return this.environment.execute(action, payload);
   }

   private JsonObject executeVisual(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      if (action.equals("discover")) return discovery("dbeaver_visual", visualActions());
      require(DBeaverMcpScope.UI);
      if (Set.of("save", "export").contains(action)) require(DBeaverMcpScope.WORKSPACE);
      else require(DBeaverMcpScope.OBSERVE);
      return this.visual.execute(action, payload);
   }

   private JsonObject executeAdmin(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      if (action.equals("discover")) return discovery("dbeaver_admin", adminActions());
      require(DBeaverMcpScope.ADMIN);
      if (!Set.of("cancel_session", "terminate_session", "run_operation").contains(action)) require(DBeaverMcpScope.QUERY);
      if (action.equals("run_operation")) require(DBeaverMcpScope.SCHEMA_WRITE);
      return this.admin.execute(action, payload, this.context.jobs());
   }

   private void require(DBeaverMcpScope... scopes) throws McpRequestException {
      Set<DBeaverMcpScope> required = Set.of(scopes);
      if (!this.context.policy().allows(required)) {
         throw new McpRequestException(-32001, "MCP policy does not allow scopes: " + required.stream().map(DBeaverMcpScope::id).sorted().toList());
      }
   }

   private static DBeaverMcpToolDefinition tool(String name, String description, List<String> actions, DBeaverMcpToolDefinition.Handler handler) {
      JsonObject action = McpJson.stringProperty("Action to execute. Use discover for arguments and safety semantics.");
      JsonArray values = new JsonArray();
      actions.forEach(values::add);
      action.add("enum", values);
      return new DBeaverMcpToolDefinition(name, description, McpJson.objectSchema(Map.of(
         "action", action,
         "arguments", McpJson.objectProperty("Action-specific arguments.")
      ), List.of("action")), Set.of(), false, false, false, handler);
   }

   private static JsonObject discovery(String facade, Map<String, String> actions) {
      JsonArray items = new JsonArray();
      actions.forEach((action, details) -> {
         JsonObject item = new JsonObject();
         item.addProperty("action", action);
         item.addProperty("arguments", details);
         items.add(item);
      });
      JsonObject result = new JsonObject();
      result.addProperty("facade", facade);
      result.addProperty("count", items.size());
      result.add("actions", items);
      return result;
   }

   private static Map<String, String> taskActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("list_types", "optional project");
      result.put("list", "optional project");
      result.put("describe", "project plus task_id or name");
      result.put("create", "project, type_id, name, optional description/folder/properties, confirm=true");
      result.put("update", "project, task_id or name, properties, confirm=true");
      result.put("delete", "project, task_id or name, confirm=true");
      result.put("run", "project, task_id or name, confirm=true; returns job_id");
      result.put("cancel_running", "project, confirm=true");
      result.put("history", "project, task_id or name, optional limit<=100");
      result.put("read_log", "project, task_id or name, run_id, optional max_chars<=65536");
      result.put("schedule", "project, task_id or name, frequency, start_time ISO-8601, recurrence, confirm=true");
      result.put("unschedule", "project, task_id or name, confirm=true");
      result.put("scheduled", "optional project");
      return result;
   }

   private static Map<String, String> projectActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("list", "no arguments");
      result.put("create", "name, optional description, confirm=true");
      result.put("rename", "project, new_name, confirm=true");
      result.put("delete", "project, confirm=true and acknowledge_delete=true");
      result.put("refresh", "project");
      result.put("list_scripts", "project, optional path and limit<=500");
      result.put("read_script", "project, path, optional max_chars<=1048576");
      result.put("write_script", "project, path ending .sql, content<=1MiB, confirm=true, overwrite=false");
      result.put("delete_script", "project, path, confirm=true");
      return result;
   }

   private static Map<String, String> environmentActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("list_drivers", "optional search/provider, limit<=500");
      result.put("describe_driver", "driver full id, id, or name");
      result.put("validate_connection_driver", "connection and optional project; checks installed libraries");
      result.put("get_preference", "key");
      result.put("set_preference", "key, primitive value, confirm=true; sensitive keys denied");
      result.put("reset_preference", "key, confirm=true; sensitive keys denied");
      return result;
   }

   private static Map<String, String> visualActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("open_erd", "connection plus object selector for schema/table/container");
      result.put("list_editors", "no arguments");
      result.put("active_editor", "no arguments");
      result.put("state", "optional editor_id");
      result.put("refresh", "optional editor_id, rearrange/reload/refresh_metadata flags");
      result.put("auto_layout", "optional editor_id");
      result.put("save", "editor_id, confirm=true");
      result.put("export", "editor_id, path under transfer root, format=png|graphml|erd, confirm=true, overwrite=false");
      return result;
   }

   private static Map<String, String> adminActions() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("list_sessions", "connection, optional project/show_idle/limit<=500");
      result.put("list_locks", "PostgreSQL connection, limit<=500");
      result.put("blocking_tree", "PostgreSQL connection, limit<=500");
      result.put("postgres_overview", "PostgreSQL connection");
      result.put("table_sizes", "PostgreSQL connection, limit<=200");
      result.put("extensions", "PostgreSQL connection, limit<=500");
      result.put("cancel_session", "connection, session_id, confirm=true");
      result.put("terminate_session", "connection, session_id, confirm=true");
      result.put("plan_operation", "connection, operation=vacuum|analyze|reindex|create_schema|install_extension plus selector/name");
      result.put("run_operation", "same as plan_operation plus confirm=true; returns job_id");
      return result;
   }
}
