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

final class DataWorkflowToolProvider implements DBeaverMcpToolProvider {
   private final DBeaverDataEditorService data = new DBeaverDataEditorService();
   private volatile DBeaverTransferService transfer;
   private DBeaverMcpContext context;

   @Override
   public String id() {
      return "data-workflows";
   }

   @Override
   public int priority() {
      return 30;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) throws Exception {
      this.context = context;
      registrar.register(tool(
         "dbeaver_data",
         "Operate native DBeaver Data Editors: open tables, inspect rows, filter, sort, stage row edits, review pending changes, save with native confirmation, or reject.",
         List.of(
            "discover", "open_table", "list_editors", "active_editor", "state", "fetch_rows",
            "set_filter", "clear_filter", "set_sort", "next_page", "refresh", "edit_cell",
            "insert_row", "delete_rows", "pending_changes", "save_changes", "reject_changes"
         ),
         this::executeData
      ));
      registrar.register(tool(
         "dbeaver_transfer",
         "Plan and run bounded CSV, JSON, or SQL exports and stage CSV/JSON imports into a native DBeaver Data Editor through the shared job API.",
         List.of("discover", "plan_export", "run_export", "plan_import", "run_import"),
         this::executeTransfer
      ));
   }

   private JsonObject executeData(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      return switch (action) {
         case "discover" -> dataDiscovery();
         case "open_table" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.openTable(payload);
         }
         case "list_editors" -> {
            require(DBeaverMcpScope.UI);
            yield this.data.listEditors();
         }
         case "active_editor" -> {
            require(DBeaverMcpScope.UI);
            yield this.data.activeEditor();
         }
         case "state" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.OBSERVE);
            yield this.data.state(payload);
         }
         case "fetch_rows" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.fetchRows(payload);
         }
         case "set_filter" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.setFilter(payload);
         }
         case "clear_filter" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.clearFilter(payload);
         }
         case "set_sort" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.setSort(payload);
         }
         case "next_page" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.nextPage(payload);
         }
         case "refresh" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.data.refresh(payload);
         }
         case "edit_cell" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            yield this.data.editCell(payload);
         }
         case "insert_row" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            yield this.data.insertRow(payload);
         }
         case "delete_rows" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            yield this.data.deleteRows(payload);
         }
         case "pending_changes" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.OBSERVE);
            yield this.data.pendingChanges(payload);
         }
         case "save_changes" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            String jobId = this.data.saveChanges(payload, this.context.jobs());
            yield queuedJob(jobId, "save_data_editor");
         }
         case "reject_changes" -> {
            require(DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            yield this.data.rejectChanges(payload);
         }
         default -> throw new IllegalArgumentException("Unknown dbeaver_data action: " + action);
      };
   }

   private JsonObject executeTransfer(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      return switch (action) {
         case "discover" -> transferDiscovery();
         case "plan_export" -> {
            require(DBeaverMcpScope.TRANSFER, DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.transfer().planExport(payload);
         }
         case "run_export" -> {
            require(DBeaverMcpScope.TRANSFER, DBeaverMcpScope.UI, DBeaverMcpScope.QUERY);
            yield this.transfer().runExport(payload);
         }
         case "plan_import" -> {
            require(DBeaverMcpScope.TRANSFER, DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            yield this.transfer().planImport(payload);
         }
         case "run_import" -> {
            require(DBeaverMcpScope.TRANSFER, DBeaverMcpScope.UI, DBeaverMcpScope.DATA_WRITE);
            yield this.transfer().runImport(payload);
         }
         default -> throw new IllegalArgumentException("Unknown dbeaver_transfer action: " + action);
      };
   }

   private DBeaverTransferService transfer() throws Exception {
      DBeaverTransferService current = this.transfer;
      if (current != null) {
         return current;
      }
      synchronized (this) {
         if (this.transfer == null) {
            this.transfer = new DBeaverTransferService(this.data, this.context.jobs());
         }
         return this.transfer;
      }
   }

   private void require(DBeaverMcpScope... scopes) throws McpRequestException {
      Set<DBeaverMcpScope> required = Set.of(scopes);
      if (!this.context.policy().allows(required)) {
         throw new McpRequestException(-32001, "MCP policy does not allow scopes: " + required.stream().map(DBeaverMcpScope::id).sorted().toList());
      }
   }

   private static DBeaverMcpToolDefinition tool(
      String name,
      String description,
      List<String> actions,
      DBeaverMcpToolDefinition.Handler handler
   ) {
      JsonObject action = McpJson.stringProperty("Action to execute. Use discover for action-specific requirements and safety semantics.");
      JsonArray values = new JsonArray();
      actions.forEach(values::add);
      action.add("enum", values);
      JsonObject schema = McpJson.objectSchema(
         Map.of(
            "action", action,
            "arguments", McpJson.objectProperty("Action-specific arguments.")
         ),
         List.of("action")
      );
      return new DBeaverMcpToolDefinition(name, description, schema, Set.of(), false, false, false, handler);
   }

   private static JsonObject dataDiscovery() {
      Map<String, String> actions = new LinkedHashMap<>();
      actions.put("open_table", "connection plus object_id, qualified_name, or name; optional where/order");
      actions.put("list_editors", "no arguments");
      actions.put("active_editor", "no arguments");
      actions.put("state", "optional editor_id; defaults to active data editor");
      actions.put("fetch_rows", "optional editor_id, offset, limit<=200, mask_sensitive=true");
      actions.put("set_filter", "editor_id, where, refresh=true");
      actions.put("clear_filter", "editor_id, refresh=true");
      actions.put("set_sort", "editor_id plus column/direction or raw order; refresh=true");
      actions.put("next_page", "editor_id");
      actions.put("refresh", "editor_id; reverts unsaved changes according to native DBeaver behavior");
      actions.put("edit_cell", "editor_id, row_index, column, value; stages only");
      actions.put("insert_row", "editor_id, values object; stages only");
      actions.put("delete_rows", "editor_id, row_indexes array; stages only");
      actions.put("pending_changes", "editor_id, optional limit<=500");
      actions.put("save_changes", "editor_id, confirm=true; submits job and opens native DBeaver save confirmation");
      actions.put("reject_changes", "editor_id, confirm=true");
      return discovery("dbeaver_data", actions);
   }

   private static JsonObject transferDiscovery() {
      Map<String, String> actions = new LinkedHashMap<>();
      actions.put("plan_export", "editor_id, path under transfer root, format=csv|json|sql, optional max_rows");
      actions.put("run_export", "same as plan_export plus confirm=true and optional overwrite=true; returns job_id");
      actions.put("plan_import", "editor_id, path under transfer root, format=csv|json, optional max_rows<=10000");
      actions.put("run_import", "same as plan_import plus confirm_stage=true; stages rows only and returns job_id");
      return discovery("dbeaver_transfer", actions);
   }

   private static JsonObject discovery(String facade, Map<String, String> actions) {
      JsonArray items = new JsonArray();
      for (Map.Entry<String, String> entry : actions.entrySet()) {
         JsonObject item = new JsonObject();
         item.addProperty("action", entry.getKey());
         item.addProperty("arguments", entry.getValue());
         items.add(item);
      }
      JsonObject result = new JsonObject();
      result.addProperty("facade", facade);
      result.addProperty("count", items.size());
      result.add("actions", items);
      return result;
   }

   private static JsonObject queuedJob(String jobId, String type) {
      JsonObject result = new JsonObject();
      result.addProperty("job_id", jobId);
      result.addProperty("type", type);
      result.addProperty("state", "queued");
      result.addProperty("status_tool", "dbeaver_job");
      return result;
   }
}
