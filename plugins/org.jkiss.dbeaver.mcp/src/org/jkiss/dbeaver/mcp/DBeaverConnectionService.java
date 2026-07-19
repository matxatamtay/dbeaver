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
import java.util.List;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceRegistry;

final class DBeaverConnectionService {
   JsonObject listConnections(JsonObject arguments) {
      String projectFilter = McpJson.getString(arguments, "project", "");
      boolean connectedOnly = McpJson.getBoolean(arguments, "connected_only", false);
      JsonArray connections = new JsonArray();

      for (DBPDataSourceContainer container : DataSourceRegistry.getAllDataSources()) {
         if ((projectFilter.isBlank() || container.getProject().getName().equals(projectFilter)) && (!connectedOnly || container.isConnected())) {
            connections.add(connectionPayload(container));
         }
      }

      JsonObject payload = new JsonObject();
      payload.addProperty("count", connections.size());
      payload.add("connections", connections);
      return payload;
   }

   DBeaverConnectionService.ResolvedConnection resolve(JsonObject arguments) throws Exception {
      return this.resolve(
         McpJson.requiredString(arguments, "connection"), McpJson.getString(arguments, "project", ""), McpJson.getBoolean(arguments, "auto_connect", true)
      );
   }

   DBeaverConnectionService.ResolvedConnection resolve(String connection, String project, boolean autoConnect) throws Exception {
      DBPDataSourceContainer container = findConnection(connection, project);
      VoidProgressMonitor monitor = new VoidProgressMonitor();
      boolean connectedByTool = false;
      if (!container.isConnected()) {
         if (!autoConnect) {
            throw new IllegalStateException("Connection is offline and auto_connect=false: " + container.getName());
         }

         if (!container.connect(monitor, true, true)) {
            throw new IllegalStateException("Connection was not established: " + container.getName());
         }

         connectedByTool = true;
      }

      DBPDataSource dataSource = container.getDataSource();
      if (dataSource == null) {
         throw new IllegalStateException("Connected data source is unavailable: " + container.getName());
      } else {
         return new DBeaverConnectionService.ResolvedConnection(container, dataSource, connectedByTool);
      }
   }

   static DBPDataSourceContainer findConnection(String connection, String project) {
      List<DBPDataSourceContainer> exactId = new ArrayList<>();
      List<DBPDataSourceContainer> exactName = new ArrayList<>();

      for (DBPDataSourceContainer container : DataSourceRegistry.getAllDataSources()) {
         if (project.isBlank() || container.getProject().getName().equals(project)) {
            if (container.getId().equals(connection)) {
               exactId.add(container);
            }

            if (container.getName().equals(connection)) {
               exactName.add(container);
            }
         }
      }

      List<DBPDataSourceContainer> matches = !exactId.isEmpty() ? exactId : exactName;
      if (matches.isEmpty()) {
         throw new IllegalArgumentException("DBeaver connection not found: " + connection);
      } else if (matches.size() > 1) {
         String choices = matches.stream()
            .map(item -> item.getProject().getName() + "/" + item.getId())
            .sorted()
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
         throw new IllegalArgumentException("Connection name is ambiguous. Pass project or connection ID. Matches: " + choices);
      } else {
         return matches.getFirst();
      }
   }

   static JsonObject connectionPayload(DBPDataSourceContainer container) {
      JsonObject item = new JsonObject();
      item.addProperty("project", container.getProject().getName());
      item.addProperty("id", container.getId());
      item.addProperty("name", container.getName());
      item.addProperty("driver_id", container.getDriver().getId());
      item.addProperty("driver", container.getDriver().getName());
      item.addProperty("connected", container.isConnected());
      item.addProperty("connecting", container.isConnecting());
      item.addProperty("read_only", container.isConnectionReadOnly());
      String error = container.getConnectionError();
      if (error != null && !error.isBlank()) {
         item.addProperty("connection_error", error);
      }

      return item;
   }

   record ResolvedConnection(DBPDataSourceContainer container, DBPDataSource dataSource, boolean connectedByTool) {
   }
}
