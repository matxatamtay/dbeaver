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

import com.google.gson.JsonObject;
import java.util.Set;

final class McpTool {
   private final String name;
   private final String description;
   private final JsonObject inputSchema;
   private final boolean readOnly;
   private final boolean destructive;
   private final boolean idempotent;
   private final Set<DBeaverMcpScope> scopes;
   private final String providerId;
   private final boolean legacy;
   private final McpTool.Handler handler;

   McpTool(String name, String description, JsonObject inputSchema, boolean readOnly, boolean destructive, McpTool.Handler handler) {
      this(
         name,
         description,
         inputSchema,
         readOnly,
         destructive,
         readOnly,
         DBeaverMcpScope.inferLegacy(name, readOnly, destructive),
         "legacy",
         true,
         handler
      );
   }

   McpTool(
      String name,
      String description,
      JsonObject inputSchema,
      boolean readOnly,
      boolean destructive,
      boolean idempotent,
      Set<DBeaverMcpScope> scopes,
      String providerId,
      boolean legacy,
      McpTool.Handler handler
   ) {
      this.name = name;
      this.description = description;
      this.inputSchema = inputSchema;
      this.readOnly = readOnly;
      this.destructive = destructive;
      this.idempotent = idempotent;
      this.scopes = Set.copyOf(scopes);
      this.providerId = providerId;
      this.legacy = legacy;
      this.handler = handler;
   }

   String name() {
      return this.name;
   }

   Set<DBeaverMcpScope> scopes() {
      return this.scopes;
   }

   JsonObject descriptor(DBeaverMcpPolicy policy) {
      JsonObject tool = new JsonObject();
      tool.addProperty("name", this.name);
      tool.addProperty("title", this.name.replace('_', ' '));
      tool.addProperty("description", this.description);
      tool.add("inputSchema", this.inputSchema.deepCopy());
      JsonObject annotations = new JsonObject();
      annotations.addProperty("readOnlyHint", this.readOnly);
      annotations.addProperty("destructiveHint", this.destructive);
      annotations.addProperty("idempotentHint", this.idempotent);
      annotations.addProperty("openWorldHint", true);
      tool.add("annotations", annotations);
      JsonObject metadata = new JsonObject();
      metadata.addProperty("provider", this.providerId);
      metadata.addProperty("legacy", this.legacy);
      metadata.addProperty("policy_allowed", policy.allows(this.scopes));
      metadata.add("scopes", DBeaverMcpScope.toJson(this.scopes));
      tool.add("_meta", metadata);
      return tool;
   }

   JsonObject execute(JsonObject arguments) throws Exception {
      return this.handler.execute(arguments);
   }

   @FunctionalInterface
   interface Handler {
      JsonObject execute(JsonObject var1) throws Exception;
   }
}
