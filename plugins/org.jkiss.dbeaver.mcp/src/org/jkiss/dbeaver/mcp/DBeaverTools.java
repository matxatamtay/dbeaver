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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;

final class DBeaverTools {
   private DBeaverTools() {
   }

   static McpToolRegistry createRegistry(int port, boolean authRequired) {
      DBeaverConnectionService connections = new DBeaverConnectionService();
      DBeaverSqlService sql = new DBeaverSqlService(connections);
      DBeaverObjectService objects = new DBeaverObjectService(connections);
      DBeaverDataService data = new DBeaverDataService(connections, sql, objects);
      DBeaverGraphService graphs = new DBeaverGraphService(connections, objects);
      DBeaverChangeService changes = new DBeaverChangeService(connections, objects);
      DBeaverSimulationService simulations = new DBeaverSimulationService(connections, objects);
      McpToolRegistry registry = new McpToolRegistry();
      registry.register(
         new McpTool(
            "dbeaver_status",
            "Check whether the embedded DBeaver MCP server is healthy and summarize the live workspace.",
            McpJson.objectSchema(Map.of()),
            true,
            false,
            arguments -> statusPayload(port, authRequired)
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_list_connections",
            "List DBeaver connections from all currently open and loaded projects. Returns IDs, names, drivers, projects, and connection state without exposing credentials.",
            McpJson.objectSchema(
               Map.of(
                  "project",
                  McpJson.stringProperty("Optional exact project name filter."),
                  "connected_only",
                  McpJson.booleanProperty("When true, return only connected data sources.")
               )
            ),
            true,
            false,
            connections::listConnections
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_execute_sql",
            "Execute SQL through a live DBeaver connection. Read queries are allowed by default. Statements that may modify data or schema require allow_write=true.",
            McpJson.objectSchema(
               props(
                  "connection",
                  McpJson.stringProperty("Required DBeaver connection ID or exact name."),
                  "project",
                  McpJson.stringProperty("Optional exact project name used to disambiguate connections."),
                  "sql",
                  McpJson.stringProperty("Required SQL statement to execute."),
                  "max_rows",
                  McpJson.integerProperty("Maximum returned rows, from 1 to 1000. Default 200.", 1, 1000),
                  "timeout_seconds",
                  McpJson.integerProperty("Statement timeout in seconds, from 1 to 300. Default 30.", 1, 300),
                  "auto_connect",
                  McpJson.booleanProperty("Connect the data source automatically when needed. Default true."),
                  "allow_write",
                  McpJson.booleanProperty("Explicitly allow statements that may modify data or schema. Default false.")
               ),
               List.of("connection", "sql")
            ),
            false,
            true,
            sql::execute
         )
      );
      registerUnderstandingTools(registry, objects, data, graphs, changes, simulations);
      return registry;
   }

   private static void registerUnderstandingTools(
      McpToolRegistry registry,
      DBeaverObjectService objects,
      DBeaverDataService data,
      DBeaverGraphService graphs,
      DBeaverChangeService changes,
      DBeaverSimulationService simulations
   ) {
      registry.register(
         new McpTool(
            "dbeaver_database_summary",
            "Summarize database product capabilities, object inventory, metadata coverage, and structural warnings.",
            McpJson.objectSchema(
               connectionProperties(
                  "max_objects",
                  McpJson.integerProperty("Maximum objects to scan. Default 5000.", 1, 10000),
                  "max_depth",
                  McpJson.integerProperty("Maximum metadata traversal depth. Default 8.", 1, 20),
                  "include_system",
                  McpJson.booleanProperty("Include system objects. Default false.")
               ),
               List.of("connection")
            ),
            true,
            false,
            objects::databaseSummary
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_list_objects",
            "List database objects through DBeaver's cross-database metadata model with type, schema, name, depth, and pagination filters.",
            McpJson.objectSchema(
               connectionProperties(
                  "types",
                  McpJson.stringArrayProperty("Optional object types such as table, view, function, procedure, trigger, index, or column."),
                  "schema",
                  McpJson.stringProperty("Optional exact schema name."),
                  "pattern",
                  McpJson.stringProperty("Optional case-insensitive substring matched against name and qualified name."),
                  "offset",
                  McpJson.integerProperty("Pagination offset. Default 0.", 0, 10000),
                  "limit",
                  McpJson.integerProperty("Maximum returned objects. Default 200.", 1, 1000),
                  "max_objects",
                  McpJson.integerProperty("Maximum objects scanned. Default 1000.", 1, 10000),
                  "max_depth",
                  McpJson.integerProperty("Maximum traversal depth. Default 8.", 1, 20),
                  "include_system",
                  McpJson.booleanProperty("Include system objects. Default false.")
               ),
               List.of("connection")
            ),
            true,
            false,
            objects::listObjects
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_find_objects",
            "Find database objects by a case-insensitive name or qualified-name fragment.",
            McpJson.objectSchema(
               connectionProperties(
                  "query",
                  McpJson.stringProperty("Required name or qualified-name fragment."),
                  "types",
                  McpJson.stringArrayProperty("Optional object-type filters."),
                  "schema",
                  McpJson.stringProperty("Optional exact schema name."),
                  "limit",
                  McpJson.integerProperty("Maximum returned objects. Default 100.", 1, 1000),
                  "include_system",
                  McpJson.booleanProperty("Include system objects. Default false.")
               ),
               List.of("connection", "query")
            ),
            true,
            false,
            objects::findObjects
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_describe_object",
            "Describe a table, view, column, function, procedure, trigger, index, constraint, or other database object in normalized JSON.",
            McpJson.objectSchema(
               selectorProperties("include_ddl", McpJson.booleanProperty("Include object DDL when available. Default false.")), List.of("connection")
            ),
            true,
            false,
            objects::describeObject
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_get_object_ddl",
            "Return DDL for a database object through DBeaver's script-object abstraction and extract best-effort lexical references.",
            McpJson.objectSchema(
               selectorProperties(
                  "fully_qualified_names",
                  McpJson.booleanProperty("Use fully qualified names. Default true."),
                  "include_nested_objects",
                  McpJson.booleanProperty("Include nested objects. Default true."),
                  "include_comments",
                  McpJson.booleanProperty("Include comments. Default true."),
                  "include_permissions",
                  McpJson.booleanProperty("Include permissions. Default false."),
                  "include_partitions",
                  McpJson.booleanProperty("Include partitions. Default true.")
               ),
               List.of("connection")
            ),
            true,
            false,
            objects::getObjectDdl
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_get_documentation",
            "Return declared object and column documentation plus clearly labeled low-confidence semantic inferences.",
            McpJson.objectSchema(selectorProperties(), List.of("connection")),
            true,
            false,
            objects::documentation
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_get_business_rules",
            "Summarize declared business rules from nullability, defaults, generated columns, constraints, foreign keys, and triggers.",
            McpJson.objectSchema(selectorProperties(), List.of("connection")),
            true,
            false,
            objects::businessRules
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_get_dependencies",
            "Return incoming and outgoing structural dependencies plus best-effort references extracted from object DDL.",
            McpJson.objectSchema(selectorProperties(), List.of("connection")),
            true,
            false,
            objects::dependencies
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_explain_trigger_flow",
            "Explain a trigger's table and statically inferred reads, writes, and routine calls, with explicit coverage limits.",
            McpJson.objectSchema(selectorProperties(), List.of("connection")),
            true,
            false,
            objects::triggerFlow
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_explain_data_change",
            "Statically explain constraints and potential triggers involved in an insert, update, delete, or merge without executing it.",
            McpJson.objectSchema(
               selectorProperties(
                  "operation",
                  McpJson.stringProperty("Required operation: insert, update, delete, or merge."),
                  "changed_columns",
                  McpJson.stringArrayProperty("Optional columns expected to change.")
               ),
               List.of("connection", "operation")
            ),
            true,
            false,
            objects::explainDataChange
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_sample_rows",
            "Return a bounded table/view sample with sensitive-column masking enabled by default and permanent masking for secrets.",
            McpJson.objectSchema(
               selectorProperties(
                  "limit",
                  McpJson.integerProperty("Maximum sampled rows. Default 20.", 1, 200),
                  "timeout_seconds",
                  McpJson.integerProperty("Query timeout. Default 30 seconds.", 1, 300),
                  "mask_sensitive",
                  McpJson.booleanProperty("Mask sensitive-looking columns. Default true.")
               ),
               List.of("connection")
            ),
            true,
            false,
            data::sampleRows
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_profile_table",
            "Profile table row count, nulls, distinct values, ranges, and top values with bounded columns and timeouts.",
            McpJson.objectSchema(
               selectorProperties(
                  "mode",
                  McpJson.stringProperty("quick or full. Quick uses a bounded sample and is the default."),
                  "sample_rows",
                  McpJson.integerProperty("Rows used by quick mode. Default 500.", 1, 1000),
                  "allow_full_scan",
                  McpJson.booleanProperty("Required true for full mode because it may scan the table once per column."),
                  "max_columns",
                  McpJson.integerProperty("Maximum profiled columns. Default 50.", 1, 200),
                  "top_value_limit",
                  McpJson.integerProperty("Top values per column. Default 5.", 1, 20),
                  "include_top_values",
                  McpJson.booleanProperty("Include top-value frequencies. Default true."),
                  "mask_sensitive",
                  McpJson.booleanProperty("Mask sensitive aggregates and values. Default true."),
                  "timeout_seconds",
                  McpJson.integerProperty("Per-query timeout. Default 30 seconds.", 1, 300)
               ),
               List.of("connection")
            ),
            true,
            false,
            data::profileTable
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_find_sensitive_data",
            "Find sensitive-looking columns from metadata names and types without returning raw values.",
            McpJson.objectSchema(
               selectorProperties(
                  "max_objects",
                  McpJson.integerProperty("Maximum objects scanned when no object selector is supplied. Default 5000.", 1, 10000),
                  "include_system",
                  McpJson.booleanProperty("Include system objects. Default false.")
               ),
               List.of("connection")
            ),
            true,
            false,
            data::findSensitiveData
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_analyze_indexes",
            "Analyze table indexes for duplicate shapes, overlapping prefixes, and foreign keys without a supporting leading-column index.",
            McpJson.objectSchema(selectorProperties(), List.of("connection")),
            true,
            false,
            data::analyzeIndexes
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_explain_query",
            "Run database-native EXPLAIN for read-only SQL. EXPLAIN ANALYZE requires explicit allow_analyze=true because it executes the query.",
            McpJson.objectSchema(
               connectionProperties(
                  "sql",
                  McpJson.stringProperty("Required read-only SQL query."),
                  "analyze",
                  McpJson.booleanProperty("Use EXPLAIN ANALYZE. Default false."),
                  "allow_analyze",
                  McpJson.booleanProperty("Explicitly acknowledge query execution by EXPLAIN ANALYZE."),
                  "timeout_seconds",
                  McpJson.integerProperty("Timeout in seconds. Default 30.", 1, 300)
               ),
               List.of("connection", "sql")
            ),
            true,
            false,
            data::explainQuery
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_profile_query",
            "Execute bounded read-only SQL and return result shape, elapsed time, warnings, and truncation state.",
            McpJson.objectSchema(
               connectionProperties(
                  "sql",
                  McpJson.stringProperty("Required read-only SQL query."),
                  "max_rows",
                  McpJson.integerProperty("Maximum rows. Default 200.", 1, 1000),
                  "timeout_seconds",
                  McpJson.integerProperty("Timeout in seconds. Default 30.", 1, 300)
               ),
               List.of("connection", "sql")
            ),
            true,
            false,
            data::profileQuery
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_get_permissions",
            "Report connection posture, effective principal, roles, grants, and row-level security. PostgreSQL uses exact catalog and privilege checks.",
            McpJson.objectSchema(selectorProperties(), List.of("connection")),
            true,
            false,
            data::permissions
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_security_summary",
            "Combine sensitive-column discovery with connection write posture, principals, grants, and PostgreSQL row-level security coverage.",
            McpJson.objectSchema(
               connectionProperties(
                  "max_objects",
                  McpJson.integerProperty("Maximum objects scanned. Default 5000.", 1, 10000),
                  "include_system",
                  McpJson.booleanProperty("Include system objects. Default false.")
               ),
               List.of("connection")
            ),
            true,
            false,
            data::securitySummary
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_trace_lineage",
            "Trace bounded upstream/downstream database lineage as a graph using exact metadata edges and heuristic DDL references.",
            McpJson.objectSchema(
               selectorProperties(
                  "direction",
                  McpJson.stringProperty("upstream, downstream, or both. Default both."),
                  "max_depth",
                  McpJson.integerProperty("Maximum graph depth. Default 4.", 1, 12),
                  "max_nodes",
                  McpJson.integerProperty("Maximum graph nodes. Default 200.", 1, 2000),
                  "relationships",
                  McpJson.stringArrayProperty("Optional relationship filters such as reads, writes, calls, fk, has_trigger, or fires_on.")
               ),
               List.of("connection")
            ),
            true,
            false,
            graphs::traceLineage
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_get_call_graph",
            "Trace routine calls, table reads/writes, and trigger relationships with bounded depth and explicit unresolved references.",
            McpJson.objectSchema(
               selectorProperties(
                  "direction",
                  McpJson.stringProperty("upstream, downstream, or both. Default both."),
                  "max_depth",
                  McpJson.integerProperty("Maximum graph depth. Default 4.", 1, 12),
                  "max_nodes",
                  McpJson.integerProperty("Maximum graph nodes. Default 200.", 1, 2000)
               ),
               List.of("connection")
            ),
            true,
            false,
            graphs::callGraph
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_compare_schemas",
            "Compare normalized database objects across two DBeaver connections or schemas, including DDL when available.",
            McpJson.objectSchema(
               props(
                  "left_connection",
                  McpJson.stringProperty("Required left connection ID or exact name."),
                  "left_project",
                  McpJson.stringProperty("Optional left project name."),
                  "left_schema",
                  McpJson.stringProperty("Optional left schema."),
                  "right_connection",
                  McpJson.stringProperty("Required right connection ID or exact name."),
                  "right_project",
                  McpJson.stringProperty("Optional right project name."),
                  "right_schema",
                  McpJson.stringProperty("Optional right schema; defaults to left_schema."),
                  "types",
                  McpJson.stringArrayProperty("Optional high-level object types to compare."),
                  "include_ddl",
                  McpJson.booleanProperty("Include normalized DDL when available. Default true."),
                  "include_unchanged",
                  McpJson.booleanProperty("Return unchanged objects. Default false."),
                  "max_objects",
                  McpJson.integerProperty("Maximum objects scanned per side. Default 3000.", 1, 10000),
                  "auto_connect",
                  McpJson.booleanProperty("Connect both sides automatically. Default true.")
               ),
               List.of("left_connection", "right_connection")
            ),
            true,
            false,
            changes::compareSchemas
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_analyze_change",
            "Analyze the likely database impact, dependents, migration order, and rollback concerns for a proposed schema change without executing it.",
            McpJson.objectSchema(
               selectorProperties(
                  "change",
                  McpJson.objectProperty("Required structured change with at least kind, such as change_type, set_not_null, rename, drop, or add_index.")
               ),
               List.of("connection", "change")
            ),
            true,
            false,
            changes::analyzeChange
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_simulate_change",
            "Execute one INSERT, UPDATE, DELETE, or MERGE in an isolated transaction, observe selected tables, and always roll it back. External side effects cannot be rolled back.",
            McpJson.objectSchema(
               connectionProperties(
                  "sql",
                  McpJson.stringProperty("Required single DML statement to simulate."),
                  "allow_simulation",
                  McpJson.booleanProperty("Required explicit acknowledgement to run transactional DML."),
                  "acknowledge_external_side_effects",
                  McpJson.booleanProperty("Required acknowledgement that rollback cannot undo external side effects."),
                  "observe_object_ids",
                  McpJson.stringArrayProperty("Optional stable table/view object IDs to snapshot before, during, and after rollback."),
                  "observation_rows",
                  McpJson.integerProperty("Maximum observed rows per object. Default 50.", 1, 200),
                  "mask_sensitive",
                  McpJson.booleanProperty("Mask sensitive-looking observed values. Default true; secrets are always masked."),
                  "timeout_seconds",
                  McpJson.integerProperty("Statement and observation timeout. Default 30 seconds.", 1, 300)
               ),
               List.of("connection", "sql", "allow_simulation", "acknowledge_external_side_effects")
            ),
            false,
            true,
            simulations::simulateChange
         )
      );
      registry.register(
         new McpTool(
            "dbeaver_understand_database",
            "Build a budgeted database-understanding bundle containing inventory, selected object details, rules, relationships, coverage, blind spots, and next queries.",
            McpJson.objectSchema(
               connectionProperties(
                  "budget",
                  McpJson.stringProperty("light, standard, or deep. Default standard."),
                  "schemas",
                  McpJson.stringArrayProperty("Optional schemas to include."),
                  "types",
                  McpJson.stringArrayProperty("Optional object types to include."),
                  "pattern",
                  McpJson.stringProperty("Optional qualified-name substring filter.")
               ),
               List.of("connection")
            ),
            true,
            false,
            objects::understandDatabase
         )
      );
   }

   private static Map<String, JsonObject> connectionProperties(Object... extra) {
      Map<String, JsonObject> result = props(
         "connection",
         McpJson.stringProperty("Required DBeaver connection ID or exact name."),
         "project",
         McpJson.stringProperty("Optional exact project name used to disambiguate connections."),
         "auto_connect",
         McpJson.booleanProperty("Connect automatically when offline. Default true.")
      );
      result.putAll(props(extra));
      return result;
   }

   private static Map<String, JsonObject> selectorProperties(Object... extra) {
      Map<String, JsonObject> result = connectionProperties(
         "object_id",
         McpJson.stringProperty("Preferred stable DBeaver object ID."),
         "qualified_name",
         McpJson.stringProperty("Exact qualified object name."),
         "name",
         McpJson.stringProperty("Exact object name; may be ambiguous."),
         "object_type",
         McpJson.stringProperty("Optional type used to disambiguate an object name.")
      );
      result.putAll(props(extra));
      return result;
   }

   private static Map<String, JsonObject> props(Object... values) {
      if (values.length % 2 != 0) {
         throw new IllegalArgumentException("Property arguments must be key/value pairs");
      } else {
         Map<String, JsonObject> result = new LinkedHashMap<>();

         for (int index = 0; index < values.length; index += 2) {
            result.put((String)values[index], (JsonObject)values[index + 1]);
         }

         return result;
      }
   }

   static JsonObject statusPayload(int port, boolean authRequired) {
      JsonObject payload = new JsonObject();
      payload.addProperty("status", "ok");
      payload.addProperty("server", "dbeaver-desktop");
      payload.addProperty("port", port);
      payload.addProperty("mcp_url", "http://127.0.0.1:" + port + "/mcp");
      payload.addProperty("auth_required", authRequired);
      payload.addProperty("projects", DBWorkbench.getPlatform().getWorkspace().getProjects().size());
      List<DBPDataSourceContainer> dataSources = DataSourceRegistry.getAllDataSources();
      payload.addProperty("connections", dataSources.size());
      payload.addProperty("connected", dataSources.stream().filter(DBPDataSourceContainer::isConnected).count());
      return payload;
   }
}
