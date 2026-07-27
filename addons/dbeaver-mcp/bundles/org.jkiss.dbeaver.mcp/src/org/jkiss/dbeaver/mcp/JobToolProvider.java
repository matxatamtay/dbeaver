/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JobToolProvider implements DBeaverMcpToolProvider {
   @Override
   public String id() {
      return "jobs";
   }

   @Override
   public int priority() {
      return 20;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      JsonObject action = McpJson.stringProperty("list, status, result, or cancel.");
      JsonArray values = new JsonArray();
      List.of("list", "status", "result", "cancel").forEach(values::add);
      action.add("enum", values);
      registrar.register(new DBeaverMcpToolDefinition(
         "dbeaver_job",
         "Inspect and control bounded asynchronous jobs submitted by DBeaver MCP tool providers.",
         McpJson.objectSchema(
            Map.of(
               "action", action,
               "job_id", McpJson.stringProperty("Required for status, result, and cancel."),
               "limit", McpJson.integerProperty("Maximum jobs returned by list. Default 20, maximum 100.", 1, 100)
            ),
            List.of("action")
         ),
         Set.of(DBeaverMcpScope.WORKSPACE),
         false,
         false,
         false,
         arguments -> execute(context.jobs(), arguments)
      ));
   }

   private static JsonObject execute(DBeaverMcpJobManager jobs, JsonObject arguments) {
      String action = McpJson.requiredString(arguments, "action");
      return switch (action) {
         case "list" -> jobs.list(McpJson.getInt(arguments, "limit", 20, 1, 100));
         case "status" -> jobs.get(McpJson.requiredString(arguments, "job_id"), false);
         case "result" -> jobs.get(McpJson.requiredString(arguments, "job_id"), true);
         case "cancel" -> jobs.cancel(McpJson.requiredString(arguments, "job_id"));
         default -> throw new IllegalArgumentException("Unknown dbeaver_job action: " + action);
      };
   }
}
