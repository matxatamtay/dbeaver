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
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;

final class DBeaverSimulationService {
   private final DBeaverConnectionService connections;
   private final DBeaverObjectService objects;

   DBeaverSimulationService(DBeaverConnectionService connections, DBeaverObjectService objects) {
      this.connections = connections;
      this.objects = objects;
   }

   JsonObject simulateChange(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "allow_simulation", false)) {
         throw new IllegalArgumentException("Set allow_simulation=true to run a transactional write simulation");
      } else if (!McpJson.getBoolean(arguments, "acknowledge_external_side_effects", false)) {
         throw new IllegalArgumentException(
            "Set acknowledge_external_side_effects=true because rollback cannot undo external calls, notifications, sequences, jobs, or autonomous transactions"
         );
      } else {
         String sql = McpJson.requiredString(arguments, "sql");
         String operation = SimulationSafety.validate(sql);
         int timeout = McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300);
         int observationRows = McpJson.getInt(arguments, "observation_rows", 50, 1, 200);
         boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
         DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
         if (!connection.dataSource().getInfo().supportsTransactions()) {
            throw new IllegalStateException("Database reports that transactions are not supported");
         } else {
            List<DBSTable> observedTables = this.resolveObservedTables(connection, arguments);
            VoidProgressMonitor monitor = new VoidProgressMonitor();
            DBCExecutionContext sourceContext = DBUtils.getDefaultContext(connection.dataSource(), false);
            DBCExecutionContext isolatedContext = connection.dataSource()
               .getDefaultInstance()
               .openIsolatedContext(monitor, "DBeaver MCP change simulation", sourceContext);
            boolean rollbackAttempted = false;
            boolean rollbackSucceeded = false;

            try {
               DBCExecutionContext e = isolatedContext;

               JsonObject var43;
               try {
                  DBCSession session = isolatedContext.openSession(monitor, DBCExecutionPurpose.UTIL, "MCP transactional change simulation");

                  try {
                     session.enableLogging(false);
                     DBCTransactionManager transactionManager = DBUtils.getTransactionManager(isolatedContext);
                     if (transactionManager == null || !transactionManager.isSupportsTransactions()) {
                        throw new IllegalStateException("Isolated execution context does not expose a transaction manager");
                     }

                     boolean originalAutoCommit = transactionManager.isAutoCommit();
                     if (!originalAutoCommit) {
                        throw new IllegalStateException("Isolated context unexpectedly opened with auto-commit disabled; simulation aborted");
                     }

                     JsonArray before = observe(session, observedTables, observationRows, timeout, maskSensitive);
                     JsonObject execution = new JsonObject();
                     Throwable executionFailure = null;

                     try {
                        transactionManager.setAutoCommit(monitor, false);
                        long startedAt = System.nanoTime();
                        DBCStatement statement = session.prepareStatement(DBCStatementType.EXEC, sql, false, false, false);

                        try {
                           statement.setStatementTimeout(timeout);
                           boolean hasResultSet = statement.executeStatement();
                           execution.addProperty("has_result_set", hasResultSet);
                           execution.addProperty("update_count", statement.getUpdateRowCount());
                           Throwable[] warnings = statement.getStatementWarnings();
                           if (warnings != null && warnings.length > 0) {
                              JsonArray warningArray = new JsonArray();

                              for (Throwable warning : warnings) {
                                 warningArray.add(McpJson.safeMessage(warning));
                              }

                              execution.add("warnings", warningArray);
                           }
                        } catch (Throwable var34) {
                           if (statement != null) {
                              try {
                                 statement.close();
                              } catch (Throwable var33) {
                                 var34.addSuppressed(var33);
                              }
                           }

                           throw var34;
                        }

                        if (statement != null) {
                           statement.close();
                        }

                        execution.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1000000.0);
                        execution.addProperty("succeeded", true);
                     } catch (Throwable var35) {
                        executionFailure = var35;
                        execution.addProperty("succeeded", false);
                        execution.addProperty("error", McpJson.safeMessage(var35));
                     }

                     JsonArray during = observe(session, observedTables, observationRows, timeout, maskSensitive);
                     rollbackAttempted = true;
                     transactionManager.rollback(session, null);
                     rollbackSucceeded = true;
                     JsonArray afterRollback = observe(session, observedTables, observationRows, timeout, maskSensitive);
                     JsonObject result = new JsonObject();
                     result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
                     result.addProperty("operation", operation);
                     result.add("execution", execution);
                     result.add("before", before);
                     result.add("during_transaction", during);
                     result.add("after_rollback", afterRollback);
                     result.addProperty("rollback_attempted", rollbackAttempted);
                     result.addProperty("rollback_succeeded", rollbackSucceeded);
                     result.addProperty("committed", false);
                     result.addProperty("isolated_context", true);
                     result.addProperty("rollback_observation_matches_before", before.equals(afterRollback));
                     JsonObject coverage = new JsonObject();
                     coverage.addProperty("database_rows", "observed_for_explicit_tables_with_row_cap");
                     coverage.addProperty("transaction_rollback", rollbackSucceeded ? "reported_success_by_driver" : "failed");
                     coverage.addProperty("external_side_effects", "unobservable");
                     result.add("coverage", coverage);
                     JsonArray blindSpots = new JsonArray();
                     blindSpots.add(
                        "Rollback may not restore sequence values, external service calls, messages, files, database jobs, or autonomous transactions."
                     );
                     blindSpots.add("Observed tables are compared only within the configured row cap and without guaranteed ordering.");
                     blindSpots.add("Triggers may affect unobserved tables.");
                     result.add("blind_spots", blindSpots);
                     if (executionFailure != null) {
                        result.addProperty("simulation_error", McpJson.safeMessage(executionFailure));
                     }

                     var43 = result;
                  } catch (Throwable var36) {
                     if (session != null) {
                        try {
                           session.close();
                        } catch (Throwable var32) {
                           var36.addSuppressed(var32);
                        }
                     }

                     throw var36;
                  }

                  if (session != null) {
                     session.close();
                  }
               } catch (Throwable var37) {
                  if (isolatedContext != null) {
                     try {
                        e.close();
                     } catch (Throwable var31) {
                        var37.addSuppressed(var31);
                     }
                  }

                  throw var37;
               }

               if (isolatedContext != null) {
                  isolatedContext.close();
               }

               return var43;
            } catch (Exception var38) {
               if (rollbackAttempted && !rollbackSucceeded) {
                  throw new IllegalStateException("Simulation failed and rollback could not be confirmed: " + McpJson.safeMessage(var38), var38);
               } else {
                  throw var38;
               }
            }
         }
      }
   }

   private List<DBSTable> resolveObservedTables(DBeaverConnectionService.ResolvedConnection connection, JsonObject arguments) throws Exception {
      List<DBSTable> result = new ArrayList<>();

      for (String objectId : McpJson.getStrings(arguments, "observe_object_ids")) {
         JsonObject selector = new JsonObject();
         selector.addProperty("object_id", objectId);
         if (!(this.objects.resolve(connection, selector) instanceof DBSTable table)) {
            throw new IllegalArgumentException("Observed object is not a table or view: " + objectId);
         }

         result.add(table);
      }

      return List.copyOf(result);
   }

   private static JsonArray observe(DBCSession session, List<DBSTable> tables, int maxRows, int timeout, boolean maskSensitive) {
      JsonArray observations = new JsonArray();

      for (DBSTable table : tables) {
         JsonObject observation = new JsonObject();
         observation.add("object", DBeaverObjectService.identity(table));

         try {
            observation.add(
               "snapshot",
               SensitiveDataPolicy.maskQueryPayload(runQuery(session, "SELECT * FROM " + DBeaverObjectService.dmlName(table), maxRows, timeout), maskSensitive)
            );
         } catch (Exception var10) {
            observation.addProperty("error", McpJson.safeMessage(var10));
         }

         observations.add(observation);
      }

      return observations;
   }

   private static JsonObject runQuery(DBCSession session, String sql, int maxRows, int timeout) throws Exception {
      JsonObject payload = new JsonObject();
      DBCStatement statement = session.prepareStatement(DBCStatementType.EXEC, sql, false, false, false);

      JsonObject var18;
      label102: {
         JsonObject var19;
         try {
            statement.setStatementTimeout(timeout);
            statement.setLimit(0L, maxRows + 1L);
            if (!statement.executeStatement()) {
               payload.addProperty("has_result_set", false);
               var18 = payload;
               break label102;
            }

            DBCResultSet resultSet = statement.openResultSet();

            try {
               JsonArray columns = new JsonArray();
               JsonArray rows = new JsonArray();
               if (resultSet != null) {
                  List<? extends DBCAttributeMetaData> attributes = resultSet.getMeta().getAttributes();

                  for (DBCAttributeMetaData attribute : attributes) {
                     JsonObject column = new JsonObject();
                     column.addProperty("name", attribute.getName());
                     column.addProperty("label", attribute.getLabel());
                     column.addProperty("type", attribute.getTypeName());
                     column.addProperty("data_kind", attribute.getDataKind().name());
                     columns.add(column);
                  }

                  boolean truncated = false;

                  while (resultSet.nextRow()) {
                     if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                     }

                     JsonObject row = new JsonObject();

                     for (int index = 0; index < attributes.size(); index++) {
                        DBCAttributeMetaData attribute = attributes.get(index);
                        row.add(McpJson.uniqueColumnKey(row, attribute.getLabel(), index), McpJson.toJsonValue(resultSet.getAttributeValue(index)));
                     }

                     rows.add(row);
                  }

                  payload.addProperty("truncated", truncated);
               }

               payload.add("columns", columns);
               payload.add("rows", rows);
               payload.addProperty("row_count", rows.size());
               var19 = payload;
            } catch (Throwable var16) {
               if (resultSet != null) {
                  try {
                     resultSet.close();
                  } catch (Throwable var15) {
                     var16.addSuppressed(var15);
                  }
               }

               throw var16;
            }

            if (resultSet != null) {
               resultSet.close();
            }
         } catch (Throwable var17) {
            if (statement != null) {
               try {
                  statement.close();
               } catch (Throwable var14) {
                  var17.addSuppressed(var14);
               }
            }

            throw var17;
         }

         if (statement != null) {
            statement.close();
         }

         return var19;
      }

      if (statement != null) {
         statement.close();
      }

      return var18;
   }
}
