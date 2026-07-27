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

final class TesterToolProvider implements DBeaverMcpToolProvider {
   private final DBeaverTestService tests;
   private DBeaverMcpContext context;

   TesterToolProvider(McpToolRegistry registry) {
      this.tests = new DBeaverTestService(registry);
   }

   @Override
   public String id() {
      return "tester-platform";
   }

   @Override
   public int priority() {
      return 50;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      this.context = context;
      JsonObject action = McpJson.stringProperty("Tester-platform action. Use discover for action-specific contracts and safety semantics.");
      JsonArray values = new JsonArray();
      List.of(
         "discover",
         "validate_case",
         "assert_json",
         "run_case",
         "run_suite",
         "wait_for",
         "capture_snapshot",
         "list_snapshots",
         "get_snapshot",
         "delete_snapshot",
         "compare_snapshots",
         "schema_drift",
         "migration_rehearsal"
      ).forEach(values::add);
      action.add("enum", values);
      registrar.register(new DBeaverMcpToolDefinition(
         "dbeaver_test",
         "Run bounded DBeaver MCP test cases, assertions, retries, suites, snapshots, schema-drift checks, and migration rehearsals.",
         McpJson.objectSchema(Map.of(
            "action", action,
            "arguments", McpJson.objectProperty("Action-specific tester arguments.")
         ), List.of("action")),
         Set.of(DBeaverMcpScope.TEST),
         false,
         false,
         false,
         this::execute
      ));
   }

   private JsonObject execute(JsonObject arguments) throws Exception {
      String action = McpJson.requiredString(arguments, "action");
      if (action.equals("discover")) return discovery();
      JsonObject payload = McpJson.getObject(arguments, "arguments");
      if (Set.of("schema_drift", "migration_rehearsal").contains(action)) {
         require(DBeaverMcpScope.QUERY);
      }
      if (action.equals("migration_rehearsal") && !McpJson.getObject(payload, "simulation").isEmpty()) {
         require(DBeaverMcpScope.DATA_WRITE);
      }
      return this.tests.execute(action, payload, this.context.jobs());
   }

   private void require(DBeaverMcpScope... scopes) throws McpRequestException {
      Set<DBeaverMcpScope> required = Set.of(scopes);
      if (!this.context.policy().allows(required)) {
         throw new McpRequestException(-32001, "MCP policy does not allow scopes: " + required.stream().map(DBeaverMcpScope::id).sorted().toList());
      }
   }

   private static JsonObject discovery() {
      Map<String, String> actions = new LinkedHashMap<>();
      actions.put("validate_case", "case or direct fields: name, tool, arguments, assertions; validates target and hints without execution");
      actions.put("assert_json", "value plus assertions[] using RFC 6901 paths and exists/equals/contains/numeric/size/type operators");
      actions.put("run_case", "case or direct fields; attempts<=6, retry_delay_ms<=10000; non-read-only targets require allow_non_read_only=true");
      actions.put("run_suite", "name, cases<=50, optional fail_fast; returns shared job_id");
      actions.put("wait_for", "case targeting a read-only tool, max_attempts<=60, delay_ms<=10000; returns shared job_id");
      actions.put("capture_snapshot", "name, read-only tool, arguments; stores bounded in-memory snapshot without retaining arguments");
      actions.put("list_snapshots", "no arguments; latest 25 snapshots");
      actions.put("get_snapshot", "snapshot_id");
      actions.put("delete_snapshot", "snapshot_id");
      actions.put("compare_snapshots", "left_snapshot_id and right_snapshot_id; returns up to 200 JSON-pointer differences");
      actions.put("schema_drift", "dbeaver_compare_schemas arguments plus max_added/max_removed/max_changed thresholds");
      actions.put("migration_rehearsal", "analysis arguments, optional simulation plus allow_simulation=true, post_checks<=20, assertions; returns shared job_id");
      JsonArray items = new JsonArray();
      actions.forEach((name, contract) -> {
         JsonObject item = new JsonObject();
         item.addProperty("action", name);
         item.addProperty("arguments", contract);
         items.add(item);
      });
      JsonObject result = new JsonObject();
      result.addProperty("facade", "dbeaver_test");
      result.addProperty("count", items.size());
      result.add("actions", items);
      JsonArray operators = new JsonArray();
      List.of("exists", "absent", "equals", "not_equals", "contains", "gt", "gte", "lt", "lte", "size_equals", "empty", "not_empty", "is_true", "is_false", "is_null", "not_null", "type").forEach(operators::add);
      result.add("assertion_operators", operators);
      return result;
   }
}
