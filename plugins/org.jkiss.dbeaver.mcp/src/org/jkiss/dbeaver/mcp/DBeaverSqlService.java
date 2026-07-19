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
import java.util.List;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

final class DBeaverSqlService {
   static final int DEFAULT_MAX_ROWS = 200;
   static final int MAX_ROWS = 1000;
   static final int DEFAULT_TIMEOUT_SECONDS = 30;
   static final int MAX_TIMEOUT_SECONDS = 300;
   DBeaverSqlService() {
   }

   JsonObject query(DBeaverConnectionService.ResolvedConnection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
      if (!SqlSafety.isReadOnly(sql)) {
         throw new IllegalArgumentException("Internal MCP query must be read-only");
      } else {
         return execute(connection, null, sql, maxRows, timeoutSeconds, true);
      }
   }

   JsonObject executeApproved(
      DBeaverConnectionService.ResolvedConnection connection,
      DBCExecutionContext executionContext,
      String sql,
      int maxRows,
      int timeoutSeconds,
      boolean readOnlySql
   ) throws Exception {
      if (executionContext != null && executionContext.getDataSource().getContainer() != connection.container()) {
         throw new IllegalArgumentException("Editor execution context does not match the approved connection");
      }
      return execute(connection, executionContext, sql, maxRows, timeoutSeconds, readOnlySql);
   }

   private static JsonObject execute(
      DBeaverConnectionService.ResolvedConnection connection,
      DBCExecutionContext executionContext,
      String sql,
      int maxRows,
      int timeoutSeconds,
      boolean readOnlySql
   ) throws Exception {
      long startedAt = System.nanoTime();
      JsonObject payload = new JsonObject();
      payload.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      payload.addProperty("connected_by_tool", connection.connectedByTool());
      payload.addProperty("read_only", readOnlySql);
      payload.addProperty("max_rows", maxRows);
      VoidProgressMonitor monitor = new VoidProgressMonitor();
      DBCSession session = executionContext != null
         ? executionContext.openSession(monitor, DBCExecutionPurpose.USER, "MCP SQL execution")
         : DBUtils.openUtilSession(monitor, connection.dataSource(), "MCP SQL execution");

      try {
         DBCStatement statement = session.prepareStatement(DBCStatementType.EXEC, sql, false, false, false);

         try {
            statement.setStatementTimeout(timeoutSeconds);
            if (readOnlySql) {
               statement.setLimit(0L, maxRows + 1L);
            }

            boolean hasResultSet = statement.executeStatement();
            payload.addProperty("has_result_set", hasResultSet);
            if (hasResultSet) {
               DBCResultSet resultSet = statement.openResultSet();

               try {
                  if (resultSet == null) {
                     payload.add("columns", new JsonArray());
                     payload.add("rows", new JsonArray());
                     payload.addProperty("row_count", 0);
                     payload.addProperty("truncated", false);
                  } else {
                     readResultSet(resultSet, maxRows, payload);
                  }
               } catch (Throwable var22) {
                  if (resultSet != null) {
                     try {
                        resultSet.close();
                     } catch (Throwable var21) {
                        var22.addSuppressed(var21);
                     }
                  }

                  throw var22;
               }

               if (resultSet != null) {
                  resultSet.close();
               }
            } else {
               payload.addProperty("update_count", statement.getUpdateRowCount());
            }

            Throwable[] warnings = statement.getStatementWarnings();
            if (warnings != null && warnings.length > 0) {
               JsonArray warningArray = new JsonArray();

               for (Throwable warning : warnings) {
                  warningArray.add(McpJson.safeMessage(warning));
               }

               payload.add("warnings", warningArray);
            }
         } catch (Throwable var23) {
            if (statement != null) {
               try {
                  statement.close();
               } catch (Throwable var20) {
                  var23.addSuppressed(var20);
               }
            }

            throw var23;
         }

         if (statement != null) {
            statement.close();
         }
      } catch (Throwable var24) {
         if (session != null) {
            try {
               session.close();
            } catch (Throwable var19) {
               var24.addSuppressed(var19);
            }
         }

         throw var24;
      }

      if (session != null) {
         session.close();
      }

      payload.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1000000.0);
      return payload;
   }

   private static void readResultSet(DBCResultSet resultSet, int maxRows, JsonObject payload) throws Exception {
      List<? extends DBCAttributeMetaData> attributes = resultSet.getMeta().getAttributes();
      JsonArray columns = new JsonArray();

      for (DBCAttributeMetaData attribute : attributes) {
         JsonObject column = new JsonObject();
         column.addProperty("name", attribute.getName());
         column.addProperty("label", attribute.getLabel());
         column.addProperty("type", attribute.getTypeName());
         column.addProperty("data_kind", attribute.getDataKind().name());
         column.addProperty("required", attribute.isRequired());
         column.addProperty("read_only", attribute.isReadOnly());
         columns.add(column);
      }

      JsonArray rows = new JsonArray();
      boolean truncated = false;

      while (resultSet.nextRow()) {
         if (rows.size() >= maxRows) {
            truncated = true;
            break;
         }

         JsonObject row = new JsonObject();

         for (int index = 0; index < attributes.size(); index++) {
            DBCAttributeMetaData attribute = attributes.get(index);
            String key = McpJson.uniqueColumnKey(row, attribute.getLabel(), index);
            row.add(key, McpJson.toJsonValue(resultSet.getAttributeValue(index)));
         }

         rows.add(row);
      }

      payload.add("columns", columns);
      payload.add("rows", rows);
      payload.addProperty("row_count", rows.size());
      payload.addProperty("truncated", truncated);
   }
}
