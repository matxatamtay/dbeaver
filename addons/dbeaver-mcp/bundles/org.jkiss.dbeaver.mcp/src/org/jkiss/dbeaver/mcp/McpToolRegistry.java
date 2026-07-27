/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jkiss.dbeaver.Log;

final class McpToolRegistry implements DBeaverMcpToolInvoker {
   private static final Log log = Log.getLog(McpToolRegistry.class);
   private final Map<String, McpTool> tools = new LinkedHashMap<>();
   private final DBeaverMcpPolicy policy;
   private final DBeaverMcpAudit audit = new DBeaverMcpAudit();

   McpToolRegistry(DBeaverMcpPolicy policy) {
      this.policy = policy;
   }

   void register(McpTool tool) {
      if (this.tools.putIfAbsent(tool.name(), tool) != null) {
         throw new IllegalArgumentException("Duplicate MCP tool: " + tool.name());
      }
   }

   void registerProvider(DBeaverMcpToolProvider provider, DBeaverMcpContext context) throws Exception {
      String providerId = provider.id();
      if (providerId == null || providerId.isBlank()) {
         throw new IllegalArgumentException("MCP tool provider id is required");
      }
      List<DBeaverMcpToolDefinition> definitions = new ArrayList<>();
      provider.registerTools(definitions::add, context);
      Set<String> providerNames = new HashSet<>();
      for (DBeaverMcpToolDefinition definition : definitions) {
         if (!providerNames.add(definition.name())) {
            throw new IllegalArgumentException("Duplicate MCP tool in provider " + providerId + ": " + definition.name());
         }
         if (this.tools.containsKey(definition.name())) {
            throw new IllegalArgumentException("Duplicate MCP tool: " + definition.name());
         }
      }
      for (DBeaverMcpToolDefinition definition : definitions) {
         this.register(
            new McpTool(
               definition.name(),
               definition.description(),
               definition.inputSchema(),
               definition.readOnly(),
               definition.destructive(),
               definition.idempotent(),
               definition.scopes(),
               providerId,
               false,
               definition.handler()::execute
            )
         );
      }
   }

   JsonObject listTools() {
      JsonArray descriptors = new JsonArray();
      this.tools.values().stream().filter(tool -> this.policy.allows(tool.scopes())).forEach(tool -> descriptors.add(tool.descriptor(this.policy)));
      JsonObject result = new JsonObject();
      result.add("tools", descriptors);
      return result;
   }

   JsonObject describeTool(String name) throws McpRequestException {
      McpTool tool = this.requireTool(name);
      return tool.descriptor(this.policy);
   }

   JsonObject executeRaw(String name, JsonObject arguments) throws Exception {
      McpTool tool = this.requireTool(name);
      if (!this.policy.allows(tool.scopes())) {
         throw new McpRequestException(-32001, "Tool is disabled by MCP policy: " + name);
      }
      long startedAt = System.nanoTime();
      try {
         JsonObject result = tool.execute(arguments);
         this.audit.record(name, true, System.nanoTime() - startedAt, "");
         return result;
      } catch (Exception e) {
         this.audit.record(name, false, System.nanoTime() - startedAt, e.getClass().getSimpleName());
         throw e;
      }
   }

   @Override
   public JsonObject invoke(String name, JsonObject arguments) throws Exception {
      return this.executeRaw(name, arguments == null ? new JsonObject() : arguments.deepCopy());
   }

   @Override
   public JsonObject describe(String name) throws Exception {
      return this.describeTool(name);
   }

   @Override
   public JsonObject list() {
      return this.listTools();
   }

   int size() {
      return this.tools.size();
   }

   DBeaverMcpAudit audit() {
      return this.audit;
   }

   JsonObject call(JsonObject params) throws McpRequestException {
      String name = McpJson.requiredString(params, "name");
      JsonObject arguments = McpJson.getObject(params, "arguments");

      try {
         return McpJson.toolResult(this.executeRaw(name, arguments), false);
      } catch (McpRequestException e) {
         throw e;
      } catch (DBeaverMcpAccessDeniedException e) {
         throw new McpRequestException(-32001, e.getMessage());
      } catch (Exception e) {
         log.warn("DBeaver MCP tool failed: " + name, e);
         JsonObject error = new JsonObject();
         error.addProperty("error", McpJson.safeMessage(e));
         error.addProperty("tool", name);
         return McpJson.toolResult(error, true);
      }
   }

   private McpTool requireTool(String name) throws McpRequestException {
      McpTool tool = this.tools.get(name);
      if (tool == null) {
         throw new McpRequestException(-32602, "Unknown tool: " + name);
      }
      return tool;
   }
}
