/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CompactToolProvider implements DBeaverMcpToolProvider {
   private final McpToolRegistry registry;

   CompactToolProvider(McpToolRegistry registry) {
      this.registry = registry;
   }

   @Override
   public String id() {
      return "compact";
   }

   @Override
   public int priority() {
      return 10;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      this.registerFacade(registrar, "dbeaver_workspace", "Compact workspace and connection operations.", map(
         "status", "dbeaver_status",
         "list_connections", "dbeaver_list_connections"
      ));
      this.registerFacade(registrar, "dbeaver_sql", "Compact SQL editor, execution, result, and transaction operations.", map(
         "open_editor", "dbeaver_open_sql_editor",
         "insert", "dbeaver_insert_sql",
         "replace", "dbeaver_replace_sql",
         "append", "dbeaver_append_sql",
         "focus", "dbeaver_focus_editor",
         "save", "dbeaver_save_sql_snippet",
         "select_connection", "dbeaver_select_connection",
         "propose", "dbeaver_propose_sql",
         "active_editor", "dbeaver_get_active_editor",
         "current_selection", "dbeaver_get_current_selection",
         "prepare_execution", "dbeaver_prepare_sql_execution",
         "execute", "dbeaver_execute_sql",
         "cancel_execution", "dbeaver_cancel_sql_execution",
         "last_result", "dbeaver_get_last_result",
         "fetch_result", "dbeaver_fetch_result",
         "last_queries", "dbeaver_get_last_queries",
         "transaction_status", "dbeaver_get_transaction_status",
         "begin_transaction", "dbeaver_begin_transaction",
         "commit", "dbeaver_commit",
         "rollback", "dbeaver_rollback",
         "profile_query", "dbeaver_profile_query",
         "explain_query", "dbeaver_explain_query"
      ));
      this.registerFacade(registrar, "dbeaver_database", "Compact database discovery, data, performance, security, and lineage operations.", map(
         "summary", "dbeaver_database_summary",
         "list_objects", "dbeaver_list_objects",
         "find_objects", "dbeaver_find_objects",
         "describe_object", "dbeaver_describe_object",
         "object_ddl", "dbeaver_get_object_ddl",
         "documentation", "dbeaver_get_documentation",
         "business_rules", "dbeaver_get_business_rules",
         "dependencies", "dbeaver_get_dependencies",
         "trigger_flow", "dbeaver_explain_trigger_flow",
         "explain_data_change", "dbeaver_explain_data_change",
         "sample_rows", "dbeaver_sample_rows",
         "profile_table", "dbeaver_profile_table",
         "find_sensitive_data", "dbeaver_find_sensitive_data",
         "analyze_indexes", "dbeaver_analyze_indexes",
         "permissions", "dbeaver_get_permissions",
         "security_summary", "dbeaver_security_summary",
         "trace_lineage", "dbeaver_trace_lineage",
         "call_graph", "dbeaver_get_call_graph",
         "understand", "dbeaver_understand_database"
      ));
      this.registerFacade(registrar, "dbeaver_change", "Compact schema comparison, impact analysis, and rollback simulation operations.", map(
         "compare_schemas", "dbeaver_compare_schemas",
         "analyze", "dbeaver_analyze_change",
         "simulate", "dbeaver_simulate_change"
      ));
   }

   private void registerFacade(DBeaverMcpToolRegistrar registrar, String name, String description, Map<String, String> actions) {
      JsonObject actionProperty = McpJson.stringProperty("Action to execute. Use discover to inspect forwarded legacy tools and schemas.");
      JsonArray values = new JsonArray();
      values.add("discover");
      actions.keySet().forEach(values::add);
      actionProperty.add("enum", values);
      JsonObject schema = McpJson.objectSchema(
         Map.of(
            "action", actionProperty,
            "arguments", McpJson.objectProperty("Arguments forwarded to the selected action's legacy tool schema.")
         ),
         List.of("action")
      );
      registrar.register(new DBeaverMcpToolDefinition(
         name,
         description,
         schema,
         Set.of(),
         false,
         false,
         false,
         arguments -> this.executeFacade(name, actions, arguments)
      ));
   }

   private JsonObject executeFacade(String facadeName, Map<String, String> actions, JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      if ("discover".equals(action)) {
         JsonArray items = new JsonArray();
         for (Map.Entry<String, String> entry : actions.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("action", entry.getKey());
            item.addProperty("tool", entry.getValue());
            item.add("descriptor", this.registry.describeTool(entry.getValue()));
            items.add(item);
         }
         JsonObject result = new JsonObject();
         result.addProperty("facade", facadeName);
         result.addProperty("count", items.size());
         result.add("actions", items);
         return result;
      }
      String target = actions.get(action);
      if (target == null) {
         throw new IllegalArgumentException("Unknown " + facadeName + " action: " + action);
      }
      return this.registry.executeRaw(target, McpJson.getObject(arguments, "arguments"));
   }

   private static Map<String, String> map(String... values) {
      if (values.length % 2 != 0) {
         throw new IllegalArgumentException("Compact action mappings must be key/value pairs");
      }
      Map<String, String> result = new LinkedHashMap<>();
      for (int index = 0; index < values.length; index += 2) {
         result.put(values[index], values[index + 1]);
      }
      return Collections.unmodifiableMap(result);
   }
}
