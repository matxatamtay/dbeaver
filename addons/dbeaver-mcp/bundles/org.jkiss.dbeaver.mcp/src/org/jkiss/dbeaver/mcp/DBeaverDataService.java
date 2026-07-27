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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableForeignKey;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndexColumn;

final class DBeaverDataService {
   private final DBeaverConnectionService connections;
   private final DBeaverSqlService sql;
   private final DBeaverObjectService objects;
   private final PostgreSqlSecurityService postgreSqlSecurity;

   DBeaverDataService(DBeaverConnectionService connections, DBeaverSqlService sql, DBeaverObjectService objects) {
      this.connections = connections;
      this.sql = sql;
      this.objects = objects;
      this.postgreSqlSecurity = new PostgreSqlSecurityService(sql, objects);
   }

   JsonObject sampleRows(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      if (this.objects.resolve(connection, arguments) instanceof DBSTable table) {
         int limit = McpJson.getInt(arguments, "limit", 20, 1, 200);
         int timeout = McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300);
         boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
         String query = "SELECT * FROM " + DBeaverObjectService.dmlName(table);
         JsonObject queryResult = this.sql.query(connection, query, limit, timeout);
         JsonObject result = SensitiveDataPolicy.maskQueryPayload(queryResult, maskSensitive);
         result.add("object", DBeaverObjectService.identity(table));
         result.addProperty("sampling_method", "first_rows_as_returned_by_database");
         result.addProperty("deterministic_order", false);
         JsonArray blindSpots = new JsonArray();
         blindSpots.add("Rows are not randomly sampled unless the database query itself defines random ordering.");
         blindSpots.add("Name-based masking may miss sensitive data stored in unexpectedly named columns.");
         result.add("blind_spots", blindSpots);
         return result;
      } else {
         throw new IllegalArgumentException("Sampling requires a table or view object");
      }
   }

   JsonObject profileTable(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      if (!(this.objects.resolve(connection, arguments) instanceof DBSTable table)) {
         throw new IllegalArgumentException("Profiling requires a table or view object");
      } else {
         int timeout = McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300);
         String mode = McpJson.getString(arguments, "mode", "quick").toLowerCase(Locale.ENGLISH);
         if (!Set.of("quick", "full").contains(mode)) {
            throw new IllegalArgumentException("mode must be quick or full");
         } else {
            int maxColumns = McpJson.getInt(arguments, "max_columns", 50, 1, 200);
            int topValueLimit = McpJson.getInt(arguments, "top_value_limit", 5, 1, 20);
            boolean includeTopValues = McpJson.getBoolean(arguments, "include_top_values", true);
            boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
            if (mode.equals("quick")) {
               int sampleRows = McpJson.getInt(arguments, "sample_rows", 500, 1, 1000);
               return this.profileSample(connection, table, sampleRows, maxColumns, topValueLimit, includeTopValues, maskSensitive, timeout);
            } else if (!McpJson.getBoolean(arguments, "allow_full_scan", false)) {
               throw new IllegalArgumentException("Set allow_full_scan=true because full profiling may scan the entire table once per column");
            } else {
               String tableName = DBeaverObjectService.dmlName(table);
               JsonObject result = new JsonObject();
               result.add("object", DBeaverObjectService.identity(table));
               result.addProperty("mode", "full");
               JsonArray blindSpots = new JsonArray();
               JsonObject countResult = this.sql.query(connection, "SELECT COUNT(*) AS row_count FROM " + tableName, 1, timeout);
               JsonObject countRow = firstRow(countResult);
               if (countRow != null && countRow.has("row_count")) {
                  result.add("row_count", countRow.get("row_count").deepCopy());
               }

               Collection<? extends DBSEntityAttribute> attributes = table.getAttributes(new VoidProgressMonitor());
               JsonArray profiles = new JsonArray();
               if (attributes != null) {
                  int processed = 0;

                  for (DBSEntityAttribute attribute : attributes) {
                     if (processed++ >= maxColumns) {
                        blindSpots.add("Column profiling stopped at max_columns=" + maxColumns + ".");
                        break;
                     }

                     JsonObject profile = new JsonObject();
                     profile.addProperty("column", attribute.getName());
                     profile.addProperty("type", attribute.getFullTypeName());
                     profile.addProperty("data_kind", attribute.getDataKind().name().toLowerCase(Locale.ENGLISH));
                     String sensitiveCategory = SensitiveDataPolicy.classify(attribute.getName(), attribute.getTypeName());
                     if (sensitiveCategory != null) {
                        profile.addProperty("sensitive_category", sensitiveCategory);
                     }

                     String quoted = DBUtils.getQuotedIdentifier(connection.dataSource(), attribute.getName());
                     String aggregateSql = "SELECT COUNT(*) AS total_count, COUNT("
                        + quoted
                        + ") AS non_null_count, COUNT(DISTINCT "
                        + quoted
                        + ") AS distinct_count, MIN("
                        + quoted
                        + ") AS min_value, MAX("
                        + quoted
                        + ") AS max_value FROM "
                        + tableName;

                     try {
                        JsonObject aggregate = firstRow(this.sql.query(connection, aggregateSql, 1, timeout));
                        if (aggregate != null) {
                           copyIfPresent(aggregate, profile, "total_count");
                           copyIfPresent(aggregate, profile, "non_null_count");
                           copyIfPresent(aggregate, profile, "distinct_count");
                           if (sensitiveCategory != null && (maskSensitive || SensitiveDataPolicy.alwaysMask(sensitiveCategory))) {
                              profile.addProperty("min_max_masked", true);
                           } else {
                              copyIfPresent(aggregate, profile, "min_value");
                              copyIfPresent(aggregate, profile, "max_value");
                           }

                           Long total = longValue(aggregate.get("total_count"));
                           Long nonNull = longValue(aggregate.get("non_null_count"));
                           if (total != null && nonNull != null) {
                              profile.addProperty("null_count", Math.max(0L, total - nonNull));
                              profile.addProperty("null_rate", total == 0L ? 0.0 : (double)(total - nonNull) / total.longValue());
                           }
                        }
                     } catch (Exception var31) {
                        profile.addProperty("aggregate_error", McpJson.safeMessage(var31));
                     }

                     if (includeTopValues) {
                        String topSql = "SELECT "
                           + quoted
                           + " AS value, COUNT(*) AS frequency FROM "
                           + tableName
                           + " GROUP BY "
                           + quoted
                           + " ORDER BY frequency DESC";

                        try {
                           JsonObject topResult = this.sql.query(connection, topSql, topValueLimit, timeout);
                           JsonArray topValues = topResult.getAsJsonArray("rows");
                           if (topValues != null) {
                              JsonArray safeValues = topValues.deepCopy();
                              if (sensitiveCategory != null && (maskSensitive || SensitiveDataPolicy.alwaysMask(sensitiveCategory))) {
                                 for (JsonElement value : safeValues) {
                                    if (value.isJsonObject() && value.getAsJsonObject().has("value") && !value.getAsJsonObject().get("value").isJsonNull()) {
                                       value.getAsJsonObject().addProperty("value", "<masked:" + sensitiveCategory + ">");
                                    }
                                 }
                              }

                              profile.add("top_values", safeValues);
                           }
                        } catch (Exception var32) {
                           profile.addProperty("top_values_error", McpJson.safeMessage(var32));
                        }
                     }

                     profiles.add(profile);
                  }
               }

               result.add("columns", profiles);
               JsonObject coverage = new JsonObject();
               coverage.addProperty("row_count", "exact_query_result");
               coverage.addProperty("column_aggregates", "best_effort_database_aggregates");
               coverage.addProperty("sensitive_detection", "name_and_type_heuristics");
               result.add("coverage", coverage);
               blindSpots.add("Aggregate support varies for complex, binary, spatial, array, and vendor-specific types.");
               blindSpots.add("Profiling can be expensive on large tables; timeout limits are enforced but no universal scan-row cap exists.");
               result.add("blind_spots", blindSpots);
               return result;
            }
         }
      }
   }

   private JsonObject profileSample(
      DBeaverConnectionService.ResolvedConnection connection,
      DBSTable table,
      int sampleRows,
      int maxColumns,
      int topValueLimit,
      boolean includeTopValues,
      boolean maskSensitive,
      int timeout
   ) throws Exception {
      JsonObject sampled = this.sql.query(connection, "SELECT * FROM " + DBeaverObjectService.dmlName(table), sampleRows, timeout);
      JsonArray columns = sampled.getAsJsonArray("columns");
      JsonArray rows = sampled.getAsJsonArray("rows");
      JsonArray profiles = new JsonArray();
      int rowCount = rows == null ? 0 : rows.size();
      if (columns != null) {
         for (int columnIndex = 0; columnIndex < columns.size() && columnIndex < maxColumns; columnIndex++) {
            JsonObject column = columns.get(columnIndex).getAsJsonObject();
            String label = McpJson.getString(column, "label", McpJson.getString(column, "name", "column_" + (columnIndex + 1)));
            String type = McpJson.getString(column, "type", "");
            String category = SensitiveDataPolicy.classify(label, type);
            int nullCount = 0;
            Map<String, DBeaverDataService.ValueCount> frequencies = new LinkedHashMap<>();
            JsonElement minimum = null;
            JsonElement maximum = null;
            if (rows != null) {
               for (JsonElement rowElement : rows) {
                  if (rowElement.isJsonObject()) {
                     JsonElement value = rowElement.getAsJsonObject().get(label);
                     if (value != null && !value.isJsonNull()) {
                        String key = McpJson.GSON.toJson(value);
                        DBeaverDataService.ValueCount current = frequencies.get(key);
                        frequencies.put(
                           key,
                           current == null
                              ? new DBeaverDataService.ValueCount(value.deepCopy(), 1)
                              : new DBeaverDataService.ValueCount(current.value(), current.count() + 1)
                        );
                        if (minimum == null || compareValues(value, minimum) < 0) {
                           minimum = value.deepCopy();
                        }

                        if (maximum == null || compareValues(value, maximum) > 0) {
                           maximum = value.deepCopy();
                        }
                     } else {
                        nullCount++;
                     }
                  }
               }
            }

            JsonObject profile = new JsonObject();
            profile.addProperty("column", label);
            profile.addProperty("type", type);
            profile.addProperty("sample_count", rowCount);
            profile.addProperty("null_count", nullCount);
            profile.addProperty("null_rate", rowCount == 0 ? 0.0 : (double)nullCount / rowCount);
            profile.addProperty("distinct_count", frequencies.size());
            if (category != null) {
               profile.addProperty("sensitive_category", category);
            }

            boolean hideValues = category != null && (maskSensitive || SensitiveDataPolicy.alwaysMask(category));
            if (minimum != null && !hideValues) {
               profile.add("min_value", minimum);
               profile.add("max_value", maximum);
            } else if (minimum != null) {
               profile.addProperty("min_max_masked", true);
            }

            if (includeTopValues) {
               JsonArray topValues = new JsonArray();
               frequencies.values().stream().sorted((left, right) -> Integer.compare(right.count(), left.count())).limit(topValueLimit).forEach(valueCount -> {
                  JsonObject value = new JsonObject();
                  if (hideValues) {
                     value.addProperty("value", "<masked:" + category + ">");
                  } else {
                     value.add("value", valueCount.value().deepCopy());
                  }

                  value.addProperty("frequency", valueCount.count());
                  topValues.add(value);
               });
               profile.add("top_values", topValues);
            }

            profiles.add(profile);
         }
      }

      JsonObject result = new JsonObject();
      result.add("object", DBeaverObjectService.identity(table));
      result.addProperty("mode", "quick");
      result.addProperty("sample_count", rowCount);
      result.addProperty("sample_truncated", sampled.has("truncated") && sampled.get("truncated").getAsBoolean());
      result.add("columns", profiles);
      JsonObject coverage = new JsonObject();
      coverage.addProperty("row_count", "sample_only");
      coverage.addProperty("column_aggregates", "computed_from_bounded_sample");
      coverage.addProperty("sensitive_detection", "name_and_type_heuristics");
      result.add("coverage", coverage);
      JsonArray blindSpots = new JsonArray();
      blindSpots.add("Quick mode uses the first bounded rows returned by the database and may not represent the full table.");
      blindSpots.add("No deterministic ordering is implied unless the underlying object defines one.");
      result.add("blind_spots", blindSpots);
      return result;
   }

   private static int compareValues(JsonElement left, JsonElement right) {
      return left.isJsonPrimitive() && right.isJsonPrimitive() && left.getAsJsonPrimitive().isNumber() && right.getAsJsonPrimitive().isNumber()
         ? Double.compare(left.getAsDouble(), right.getAsDouble())
         : left.toString().compareTo(right.toString());
   }

   JsonObject findSensitiveData(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      List<DBSObject> targets = new ArrayList<>();
      if (hasObjectSelector(arguments)) {
         targets.add(this.objects.resolve(connection, arguments));
      } else {
         int maxObjects = McpJson.getInt(arguments, "max_objects", 5000, 1, 10000);
         DBeaverObjectService.ScanResult scan = this.objects
            .scan(connection.dataSource(), maxObjects, 8, McpJson.getBoolean(arguments, "include_system", false));
         scan.objects().stream().map(DBeaverObjectService.ScannedObject::object).filter(DBSEntity.class::isInstance).forEach(targets::add);
      }

      JsonArray findings = new JsonArray();
      VoidProgressMonitor monitor = new VoidProgressMonitor();
      Iterator result = targets.iterator();

      while (true) {
         DBSObject target;
         Collection<? extends DBSEntityAttribute> attributes;
         while (true) {
            if (!result.hasNext()) {
               JsonObject resultx = new JsonObject();
               resultx.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
               resultx.addProperty("finding_count", findings.size());
               resultx.add("findings", findings);
               JsonObject coverage = new JsonObject();
               coverage.addProperty("method", "metadata_name_and_type_heuristics");
               coverage.addProperty("sample_values_examined", false);
               resultx.add("coverage", coverage);
               JsonArray blindSpots = new JsonArray();
               blindSpots.add("Sensitive values in unexpectedly named columns may not be detected.");
               blindSpots.add("No raw sensitive values are returned by this tool.");
               resultx.add("blind_spots", blindSpots);
               return resultx;
            }

            target = (DBSObject)result.next();
            if (target instanceof DBSEntity entity) {
               try {
                  attributes = entity.getAttributes(monitor);
                  break;
               } catch (Exception var14) {
               }
            }
         }

         if (attributes != null) {
            for (DBSEntityAttribute attribute : attributes) {
               String category = SensitiveDataPolicy.classify(attribute.getName(), attribute.getTypeName());
               if (category != null) {
                  JsonObject finding = new JsonObject();
                  finding.add("object", DBeaverObjectService.identity(target));
                  finding.addProperty("column", attribute.getName());
                  finding.addProperty("type", attribute.getFullTypeName());
                  finding.addProperty("category", category);
                  finding.addProperty("confidence", "heuristic_name_and_type");
                  finding.addProperty("always_masked", SensitiveDataPolicy.alwaysMask(category));
                  findings.add(finding);
               }
            }
         }
      }
   }

   JsonObject analyzeIndexes(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      if (!(this.objects.resolve(connection, arguments) instanceof DBSTable table)) {
         throw new IllegalArgumentException("Index analysis requires a table object");
      } else {
         VoidProgressMonitor monitor = new VoidProgressMonitor();
         Collection<? extends DBSTableIndex> indexes = table.getIndexes(monitor);
         List<DBeaverDataService.IndexShape> shapes = new ArrayList<>();
         JsonArray indexList = new JsonArray();
         if (indexes != null) {
            for (DBSTableIndex index : indexes) {
               DBeaverDataService.IndexShape shape = indexShape(index, monitor);
               shapes.add(shape);
               JsonObject item = new JsonObject();
               item.add("index", DBeaverObjectService.identity(index));
               item.addProperty("unique", index.isUnique());
               item.addProperty("primary", index.isPrimary());
               JsonArray columns = new JsonArray();
               shape.columns().forEach(columns::add);
               item.add("columns", columns);
               indexList.add(item);
            }
         }

         JsonArray recommendations = new JsonArray();

         for (int left = 0; left < shapes.size(); left++) {
            for (int right = left + 1; right < shapes.size(); right++) {
               DBeaverDataService.IndexShape a = (DBeaverDataService.IndexShape)shapes.get(left);
               DBeaverDataService.IndexShape b = (DBeaverDataService.IndexShape)shapes.get(right);
               if (a.columns().equals(b.columns())) {
                  recommendations.add(
                     recommendation(
                        "duplicate_index_shape",
                        a.name() + " and " + b.name() + " index the same ordered columns",
                        "high",
                        "Review uniqueness, predicates, included columns, and vendor-specific options before removing either index."
                     )
                  );
               } else if (startsWith(a.columns(), b.columns()) || startsWith(b.columns(), a.columns())) {
                  recommendations.add(
                     recommendation(
                        "overlapping_index_prefix",
                        a.name() + " and " + b.name() + " have overlapping leading columns",
                        "medium",
                        "Check workload and included/predicate columns before consolidating."
                     )
                  );
               }
            }
         }

         Collection<? extends DBSEntityAssociation> associations = table.getAssociations(monitor);
         if (associations != null) {
            for (DBSEntityAssociation association : associations) {
               if (association instanceof DBSTableForeignKey foreignKey) {
                  List<String> fkColumns = referenceColumns(foreignKey, monitor);
                  boolean supported = shapes.stream().anyMatch(shape -> startsWith(shape.columns(), fkColumns));
                  if (!fkColumns.isEmpty() && !supported) {
                     recommendations.add(
                        recommendation(
                           "foreign_key_without_supporting_index",
                           foreignKey.getName() + " uses columns " + String.join(", ", fkColumns),
                           "medium",
                           "A supporting index may improve joins and parent-row updates/deletes, depending on workload."
                        )
                     );
                  }
               }
            }
         }

         JsonObject result = new JsonObject();
         result.add("table", DBeaverObjectService.identity(table));
         result.add("indexes", indexList);
         result.add("recommendations", recommendations);
         JsonObject coverage = new JsonObject();
         coverage.addProperty("index_definitions", "exact_for_loaded_metadata");
         coverage.addProperty("usage_statistics", "not_available_in_generic_adapter");
         coverage.addProperty("workload_analysis", "not_performed");
         result.add("coverage", coverage);
         return result;
      }
   }

   JsonObject explainQuery(JsonObject arguments) throws Exception {
      String query = McpJson.requiredString(arguments, "sql");
      if (!SqlSafety.isReadOnly(query)) {
         throw new IllegalArgumentException("Query explanation only accepts read-only SQL");
      } else {
         boolean analyze = McpJson.getBoolean(arguments, "analyze", false);
         if (analyze && !McpJson.getBoolean(arguments, "allow_analyze", false)) {
            throw new IllegalArgumentException("Set allow_analyze=true because EXPLAIN ANALYZE executes the query");
         } else {
            DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
            int timeout = McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300);
            DatabaseIntrospector introspector = DatabaseIntrospectors.forDataSource(connection.dataSource());
            if (analyze && !introspector.supportsExplainAnalyze()) {
               throw new IllegalArgumentException(introspector.id() + " adapter does not support EXPLAIN ANALYZE");
            } else {
               JsonObject result = this.sql.query(connection, introspector.explainSql(query, analyze), 1000, timeout);
               result.addProperty("introspector", introspector.id());
               result.addProperty("analyze", analyze);
               result.addProperty("query_executed", analyze);
               boolean structured = false;
               if ("postgresql".equals(introspector.id())) {
                  JsonObject first = firstRow(result);
                  JsonElement rawPlan = first == null ? null : first.get("QUERY PLAN");
                  if (rawPlan != null && rawPlan.isJsonPrimitive()) {
                     try {
                        result.add("structured_plan", JsonParser.parseString(rawPlan.getAsString()));
                        result.addProperty("plan_format", "postgresql_json");
                        structured = true;
                     } catch (RuntimeException var12) {
                        result.addProperty("plan_format", "database_native_rows");
                     }
                  }
               }

               if (!structured && !result.has("plan_format")) {
                  result.addProperty("plan_format", "database_native_rows");
               }

               JsonObject coverage = new JsonObject();
               coverage.addProperty("plan", "database_native_explain_output");
               coverage.addProperty("structured_plan_tree", structured);
               result.add("coverage", coverage);
               return result;
            }
         }
      }
   }

   JsonObject profileQuery(JsonObject arguments) throws Exception {
      String query = McpJson.requiredString(arguments, "sql");
      if (!SqlSafety.isReadOnly(query)) {
         throw new IllegalArgumentException("Query profiling only accepts read-only SQL");
      } else {
         DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
         int maxRows = McpJson.getInt(arguments, "max_rows", 200, 1, 1000);
         int timeout = McpJson.getInt(arguments, "timeout_seconds", 30, 1, 300);
         JsonObject result = this.sql.query(connection, query, maxRows, timeout);
         result.addProperty("profiling_mode", "bounded_execution");
         result.addProperty("read_only_guard", true);
         return result;
      }
   }

   JsonObject permissions(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      if (this.postgreSqlSecurity.supports(connection)) {
         return this.postgreSqlSecurity.inspect(connection, arguments);
      } else {
         JsonObject result = new JsonObject();
         result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
         DBPDataSourceInfo info = connection.dataSource().getInfo();
         result.addProperty("connection_read_only", connection.container().isConnectionReadOnly());
         result.addProperty("database_read_only_data", info.isReadOnlyData());
         result.addProperty("database_read_only_metadata", info.isReadOnlyMetaData());
         result.addProperty("effective_principal", "unavailable_in_generic_dbeaver_model");
         result.add("roles", new JsonArray());
         result.add("object_grants", new JsonArray());
         JsonObject coverage = new JsonObject();
         coverage.addProperty("read_only_state", "exact");
         coverage.addProperty("roles_and_grants", "requires_database_specific_adapter");
         result.add("coverage", coverage);
         JsonArray blindSpots = new JsonArray();
         blindSpots.add("The generic DBeaver model does not expose effective database roles, object grants, or row-level security policies.");
         result.add("blind_spots", blindSpots);
         return result;
      }
   }

   JsonObject securitySummary(JsonObject arguments) throws Exception {
      JsonObject sensitive = this.findSensitiveData(arguments);
      JsonObject permissions = this.permissions(arguments);
      JsonObject result = new JsonObject();
      result.add("connection", permissions.get("connection").deepCopy());
      result.add("sensitive_data", sensitive);
      result.add("permissions", permissions);
      JsonArray risks = new JsonArray();
      if (sensitive.get("finding_count").getAsInt() > 0 && !permissions.get("connection_read_only").getAsBoolean()) {
         risks.add(
            recommendation(
               "writable_connection_with_sensitive_columns",
               "The connection is writable and sensitive-looking columns were found.",
               "medium",
               "Use a read-only database principal for exploratory agent access where possible."
            )
         );
      }

      result.add("risks", risks);
      return result;
   }

   private static JsonObject firstRow(JsonObject queryResult) {
      JsonArray rows = queryResult.getAsJsonArray("rows");
      return rows != null && !rows.isEmpty() && rows.get(0).isJsonObject() ? rows.get(0).getAsJsonObject() : null;
   }

   private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
      if (source.has(key)) {
         target.add(key, source.get(key).deepCopy());
      }
   }

   private static Long longValue(JsonElement element) {
      if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
         try {
            return element.getAsLong();
         } catch (RuntimeException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static boolean hasObjectSelector(JsonObject arguments) {
      return !McpJson.getString(arguments, "object_id", "").isBlank()
         || !McpJson.getString(arguments, "qualified_name", "").isBlank()
         || !McpJson.getString(arguments, "name", "").isBlank();
   }

   private static DBeaverDataService.IndexShape indexShape(DBSTableIndex index, VoidProgressMonitor monitor) throws Exception {
      List<String> columns = new ArrayList<>();
      List<? extends DBSTableIndexColumn> refs = index.getAttributeReferences(monitor);
      if (refs != null) {
         for (DBSTableIndexColumn ref : refs) {
            String name = ref.getAttribute() == null ? ref.getName() : ref.getAttribute().getName();
            columns.add(name.toLowerCase(Locale.ENGLISH));
         }
      }

      return new DBeaverDataService.IndexShape(index.getName(), List.copyOf(columns));
   }

   private static List<String> referenceColumns(DBSTableForeignKey foreignKey, VoidProgressMonitor monitor) throws Exception {
      List<String> columns = new ArrayList<>();
      List<? extends DBSEntityAttributeRef> refs = foreignKey.getAttributeReferences(monitor);
      if (refs != null) {
         for (DBSEntityAttributeRef ref : refs) {
            if (ref.getAttribute() != null) {
               columns.add(ref.getAttribute().getName().toLowerCase(Locale.ENGLISH));
            }
         }
      }

      return List.copyOf(columns);
   }

   private static boolean startsWith(List<String> indexColumns, List<String> prefix) {
      return !prefix.isEmpty() && indexColumns.size() >= prefix.size() && indexColumns.subList(0, prefix.size()).equals(prefix);
   }

   private static JsonObject recommendation(String kind, String evidence, String confidence, String limitation) {
      JsonObject result = new JsonObject();
      result.addProperty("kind", kind);
      result.addProperty("evidence", evidence);
      result.addProperty("confidence", confidence);
      result.addProperty("limitation", limitation);
      return result;
   }

   private record IndexShape(String name, List<String> columns) {
   }

   private record ValueCount(JsonElement value, int count) {
   }
}
