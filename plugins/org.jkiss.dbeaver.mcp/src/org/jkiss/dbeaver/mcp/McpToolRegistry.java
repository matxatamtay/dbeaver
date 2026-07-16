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
import java.util.LinkedHashMap;
import java.util.Map;
import org.jkiss.dbeaver.Log;

final class McpToolRegistry {
   private static final Log log = Log.getLog(McpToolRegistry.class);
   private final Map<String, McpTool> tools = new LinkedHashMap<>();

   void register(McpTool tool) {
      if (this.tools.putIfAbsent(tool.name(), tool) != null) {
         throw new IllegalArgumentException("Duplicate MCP tool: " + tool.name());
      }
   }

   JsonObject listTools() {
      JsonArray descriptors = new JsonArray();
      this.tools.values().forEach(tool -> descriptors.add(tool.descriptor()));
      JsonObject result = new JsonObject();
      result.add("tools", descriptors);
      return result;
   }

   JsonObject call(JsonObject params) throws McpRequestException {
      String name = McpJson.requiredString(params, "name");
      McpTool tool = this.tools.get(name);
      if (tool == null) {
         throw new McpRequestException(-32602, "Unknown tool: " + name);
      } else {
         JsonObject arguments = McpJson.getObject(params, "arguments");

         try {
            return McpJson.toolResult(tool.execute(arguments), false);
         } catch (McpRequestException var7) {
            throw var7;
         } catch (Exception var8) {
            log.warn("DBeaver MCP tool failed: " + name, var8);
            JsonObject error = new JsonObject();
            error.addProperty("error", McpJson.safeMessage(var8));
            error.addProperty("tool", name);
            return McpJson.toolResult(error, true);
         }
      }
   }
}
