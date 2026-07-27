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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPHiddenObject;
import org.jkiss.dbeaver.model.DBPObjectWithLongId;
import org.jkiss.dbeaver.model.DBPQualifiedObject;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPSystemObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.DBSObjectType;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameter;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableCheckConstraint;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableForeignKey;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndexColumn;
import org.jkiss.dbeaver.model.struct.rdb.DBSTablePartition;
import org.jkiss.dbeaver.model.struct.rdb.DBSTrigger;

final class DBeaverObjectService {
   static final int DEFAULT_MAX_OBJECTS = 1000;
   static final int MAX_OBJECTS = 10000;
   static final int DEFAULT_MAX_DEPTH = 8;
   static final int MAX_DEPTH = 20;
   private final DBeaverConnectionService connections;

   DBeaverObjectService(DBeaverConnectionService connections) {
      this.connections = connections;
   }

   JsonObject databaseSummary(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      int maxObjects = McpJson.getInt(arguments, "max_objects", 5000, 1, 10000);
      int maxDepth = McpJson.getInt(arguments, "max_depth", 8, 1, 20);
      boolean includeSystem = McpJson.getBoolean(arguments, "include_system", false);
      DBeaverObjectService.ScanResult scan = this.scan(connection.dataSource(), maxObjects, maxDepth, includeSystem);
      JsonObject result = new JsonObject();
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      JsonObject database = new JsonObject();
      DBPDataSourceInfo info = connection.dataSource().getInfo();
      database.addProperty("product", info.getDatabaseProductName());
      database.addProperty("version", info.getDatabaseProductVersion());
      database.addProperty("driver", info.getDriverName());
      database.addProperty("driver_version", info.getDriverVersion());
      database.addProperty("read_only_data", info.isReadOnlyData());
      database.addProperty("read_only_metadata", info.isReadOnlyMetaData());
      database.addProperty("supports_transactions", info.supportsTransactions());
      database.addProperty("supports_savepoints", info.supportsSavepoints());
      database.addProperty("supports_referential_integrity", info.supportsReferentialIntegrity());
      database.addProperty("supports_indexes", info.supportsIndexes());
      database.addProperty("supports_stored_code", info.supportsStoredCode());
      DatabaseIntrospector introspector = DatabaseIntrospectors.forDataSource(connection.dataSource());
      database.addProperty("introspector", introspector.id());
      database.add("mcp_capabilities", introspector.capabilities());
      if ("postgresql".equals(introspector.id())) {
         database.add("extensions", postgreSqlExtensions(connection.dataSource(), new VoidProgressMonitor()));
      }

      result.add("database", database);
      Map<String, Integer> counts = new LinkedHashMap<>();
      int tablesWithoutPrimaryKey = 0;
      int invalidObjects = 0;
      VoidProgressMonitor monitor = new VoidProgressMonitor();

      for (DBeaverObjectService.ScannedObject scanned : scan.objects()) {
         String type = objectType(scanned.object());
         counts.merge(type, 1, Integer::sum);
         if (scanned.object() instanceof DBSTable table && !table.isView() && !hasPrimaryKey(table, monitor)) {
            tablesWithoutPrimaryKey++;
         }

         if (scanned.object() instanceof DBPStatefulObject stateful
            && !"Normal".equalsIgnoreCase(stateful.getObjectState().getTitle())
            && !"Active".equalsIgnoreCase(stateful.getObjectState().getTitle())) {
            invalidObjects++;
         }
      }

      JsonObject countJson = new JsonObject();
      counts.forEach(countJson::addProperty);
      result.add("object_counts", countJson);
      result.addProperty("scanned_objects", scan.objects().size());
      result.addProperty("tables_without_primary_key", tablesWithoutPrimaryKey);
      result.addProperty("non_normal_objects", invalidObjects);
      result.addProperty("truncated", scan.truncated());
      result.add("supported_object_types", supportedObjectTypes(connection.dataSource()));
      addCoverage(
         result,
         scan,
         "generic_dbeaver_model",
         List.of(
            "Database-specific dependencies, permissions, trigger timing, and routine bodies may require a dialect adapter.",
            "Application-level business logic is outside database metadata."
         )
      );
      return result;
   }

   JsonObject listObjects(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      int maxObjects = McpJson.getInt(arguments, "max_objects", 1000, 1, 10000);
      int maxDepth = McpJson.getInt(arguments, "max_depth", 8, 1, 20);
      int offset = McpJson.getInt(arguments, "offset", 0, 0, 10000);
      int limit = McpJson.getInt(arguments, "limit", 200, 1, 1000);
      boolean includeSystem = McpJson.getBoolean(arguments, "include_system", false);
      String pattern = McpJson.getString(arguments, "pattern", "").toLowerCase(Locale.ENGLISH);
      String schema = McpJson.getString(arguments, "schema", "");
      Set<String> types = lowerSet(McpJson.getStrings(arguments, "types"));
      DBeaverObjectService.ScanResult scan = this.scan(connection.dataSource(), maxObjects, maxDepth, includeSystem);
      List<DBeaverObjectService.ScannedObject> matches = scan.objects()
         .stream()
         .filter(item -> types.isEmpty() || types.contains(objectType(item.object())))
         .filter(
            item -> pattern.isBlank()
               || item.object().getName().toLowerCase(Locale.ENGLISH).contains(pattern)
               || qualifiedName(item.object()).toLowerCase(Locale.ENGLISH).contains(pattern)
         )
         .filter(item -> schema.isBlank() || schema.equalsIgnoreCase(schemaName(item.object())))
         .toList();
      JsonArray objects = new JsonArray();

      for (int index = offset; index < matches.size() && objects.size() < limit; index++) {
         JsonObject identity = identity(matches.get(index).object());
         identity.addProperty("depth", matches.get(index).depth());
         objects.add(identity);
      }

      JsonObject result = new JsonObject();
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      result.addProperty("matched", matches.size());
      result.addProperty("offset", offset);
      result.addProperty("returned", objects.size());
      result.addProperty("truncated", scan.truncated() || offset + objects.size() < matches.size());
      result.add("objects", objects);
      addCoverage(result, scan, "generic_dbeaver_model", List.of());
      return result;
   }

   JsonObject findObjects(JsonObject arguments) throws Exception {
      String query = McpJson.requiredString(arguments, "query");
      JsonObject listArguments = arguments.deepCopy();
      listArguments.remove("query");
      listArguments.addProperty("pattern", query);
      if (!listArguments.has("limit")) {
         listArguments.addProperty("limit", 100);
      }

      return this.listObjects(listArguments);
   }

   JsonObject describeObject(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.resolve(connection, arguments);
      boolean includeDdl = McpJson.getBoolean(arguments, "include_ddl", false);
      return this.describe(connection, object, includeDdl);
   }

   JsonObject getObjectDdl(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.resolve(connection, arguments);
      JsonObject result = identity(object);
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      String ddl = readDdl(object, new VoidProgressMonitor(), arguments);
      result.addProperty("ddl", ddl);
      result.addProperty("ddl_length", ddl.length());
      result.addProperty("dynamic_sql_detected", DdlReferenceExtractor.hasDynamicSql(ddl));
      JsonArray references = new JsonArray();

      for (DdlReferenceExtractor.Reference reference : DdlReferenceExtractor.extract(ddl)) {
         JsonObject item = new JsonObject();
         item.addProperty("operation", reference.operation());
         item.addProperty("object", reference.objectName());
         references.add(item);
      }

      result.add("lexical_references", references);
      JsonObject coverage = new JsonObject();
      coverage.addProperty("ddl", "exact_from_dbeaver_object_definition");
      coverage.addProperty("references", "heuristic_sql_text_analysis");
      result.add("coverage", coverage);
      return result;
   }

   JsonObject documentation(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.resolve(connection, arguments);
      JsonObject result = new JsonObject();
      result.add("object", identity(object));
      JsonObject declared = new JsonObject();
      if (object.getDescription() != null && !object.getDescription().isBlank()) {
         declared.addProperty("description", object.getDescription());
      }

      VoidProgressMonitor monitor = new VoidProgressMonitor();
      if (object instanceof DBSEntity entity) {
         JsonArray columns = new JsonArray();
         Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
         if (attributes != null) {
            for (DBSEntityAttribute attribute : attributes) {
               if (attribute.getDescription() != null && !attribute.getDescription().isBlank()) {
                  JsonObject item = new JsonObject();
                  item.addProperty("column", attribute.getName());
                  item.addProperty("description", attribute.getDescription());
                  columns.add(item);
               }
            }
         }

         declared.add("columns", columns);
      }

      if (object instanceof DBSProcedure procedure) {
         JsonArray parameters = new JsonArray();
         Collection<? extends DBSProcedureParameter> values = procedure.getParameters(monitor);
         if (values != null) {
            for (DBSProcedureParameter parameter : values) {
               if (parameter.getDescription() != null && !parameter.getDescription().isBlank()) {
                  JsonObject item = new JsonObject();
                  item.addProperty("parameter", parameter.getName());
                  item.addProperty("description", parameter.getDescription());
                  parameters.add(item);
               }
            }
         }

         declared.add("parameters", parameters);
      }

      result.add("declared_documentation", declared);
      result.add("inferred_semantics", inferSemantics(object, monitor));
      JsonObject coverage = new JsonObject();
      coverage.addProperty("declared_documentation", "complete_for_loaded_dbeaver_metadata");
      coverage.addProperty("inferred_semantics", "name_and_constraint_heuristics");
      result.add("coverage", coverage);
      return result;
   }

   JsonObject businessRules(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.resolve(connection, arguments);
      if (!(object instanceof DBSEntity entity)) {
         throw new IllegalArgumentException("Business rules currently require a table or view object");
      } else {
         VoidProgressMonitor monitor = new VoidProgressMonitor();
         JsonArray rules = new JsonArray();
         Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
         if (attributes != null) {
            for (DBSEntityAttribute attribute : attributes) {
               if (!isHiddenObject(attribute)) {
                  if (attribute.isRequired()) {
                     rules.add(rule(attribute.getName() + " must not be null", "column_nullability", objectId(attribute), "declared"));
                  }

                  if (attribute.getDefaultValue() != null && !attribute.getDefaultValue().isBlank()) {
                     rules.add(rule(attribute.getName() + " defaults to " + attribute.getDefaultValue(), "column_default", objectId(attribute), "declared"));
                  }

                  if (attribute.isAutoGenerated()) {
                     rules.add(rule(attribute.getName() + " is generated by the database", "generated_column", objectId(attribute), "declared"));
                  }
               }
            }
         }

         Collection<? extends DBSEntityConstraint> constraints = entity.getConstraints(monitor);
         if (constraints != null) {
            for (DBSEntityConstraint constraint : constraints) {
               String text = constraint.getConstraintType().getName() + " " + constraint.getName();
               if (constraint instanceof DBSTableCheckConstraint check && check.getCheckConstraintDefinition() != null) {
                  text = text + ": " + check.getCheckConstraintDefinition();
               }

               rules.add(rule(text, "constraint", objectId(constraint), "declared"));
            }
         }

         if (object instanceof DBSTable table) {
            List<? extends DBSTrigger> triggers = table.getTriggers(monitor);
            if (triggers != null) {
               for (DBSTrigger trigger : triggers) {
                  rules.add(rule("Changes may invoke trigger " + trigger.getName(), "trigger", objectId(trigger), "derived"));
               }
            }
         }

         JsonObject result = new JsonObject();
         result.add("object", identity(object));
         result.addProperty("count", rules.size());
         result.add("rules", rules);
         JsonObject coverage = new JsonObject();
         coverage.addProperty("constraints", "exact_for_loaded_metadata");
         coverage.addProperty("trigger_behavior", "partial_until_trigger_ddl_is_analyzed");
         result.add("coverage", coverage);
         return result;
      }
   }

   JsonObject dependencies(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.resolve(connection, arguments);
      return this.dependencies(connection, object, null);
   }

   JsonObject dependencies(DBeaverConnectionService.ResolvedConnection connection, DBSObject object, DBeaverObjectService.ScanResult knownScan) throws Exception {
      VoidProgressMonitor monitor = new VoidProgressMonitor();
      JsonArray edges = new JsonArray();
      if (object instanceof DBSEntity entity) {
         Collection<? extends DBSEntityAssociation> associations = entity.getAssociations(monitor);
         if (associations != null) {
            for (DBSEntityAssociation association : associations) {
               DBSObject target = association.getAssociatedEntity();
               edges.add(edge(object, target, association.getConstraintType().getId(), "database_metadata", "exact"));
            }
         }

         Collection<? extends DBSEntityAssociation> references = entity.getReferences(monitor);
         if (references != null) {
            for (DBSEntityAssociation reference : references) {
               edges.add(edge(reference.getParentObject(), object, reference.getConstraintType().getId(), "database_metadata", "exact"));
            }
         }
      }

      if (object instanceof DBSTable table) {
         List<? extends DBSTrigger> triggers = table.getTriggers(monitor);
         if (triggers != null) {
            for (DBSTrigger trigger : triggers) {
               edges.add(edge(table, trigger, "has_trigger", "database_metadata", "exact"));
            }
         }
      }

      if (object instanceof DBSTrigger trigger && trigger.getTable() != null) {
         edges.add(edge(trigger, trigger.getTable(), "fires_on", "database_metadata", "exact"));
      }

      String ddl = tryReadDdl(object, monitor);
      boolean dynamicSql = false;
      if (ddl != null) {
         dynamicSql = DdlReferenceExtractor.hasDynamicSql(ddl);
         DBeaverObjectService.ScanResult referenceScan = knownScan == null ? this.scan(connection.dataSource(), 5000, 8, true) : knownScan;

         for (DdlReferenceExtractor.Reference reference : DdlReferenceExtractor.extract(ddl)) {
            DBSObject target = findReference(referenceScan.objects(), reference.objectName());
            edges.add(
               edge(
                  object,
                  target,
                  relationshipForOperation(reference.operation()),
                  "ddl_text",
                  target == null ? "heuristic_unresolved" : "heuristic_resolved",
                  reference.objectName()
               )
            );
         }
      }

      JsonObject result = new JsonObject();
      result.add("object", identity(object));
      result.addProperty("edge_count", edges.size());
      result.add("edges", edges);
      result.addProperty("dynamic_sql_detected", dynamicSql);
      JsonObject coverage = new JsonObject();
      coverage.addProperty("structural_relationships", "exact_for_loaded_metadata");
      coverage.addProperty("routine_and_view_references", ddl == null ? "unavailable" : "heuristic_ddl_analysis");
      result.add("coverage", coverage);
      JsonArray blindSpots = new JsonArray();
      if (dynamicSql) {
         blindSpots.add("Dynamic SQL was detected; dependency analysis is incomplete.");
      }

      if (ddl == null) {
         blindSpots.add("Object DDL is unavailable to the current user or driver.");
      }

      result.add("blind_spots", blindSpots);
      return result;
   }

   JsonObject triggerFlow(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      if (this.resolve(connection, arguments) instanceof DBSTrigger trigger) {
         VoidProgressMonitor monitor = new VoidProgressMonitor();
         JsonObject result = new JsonObject();
         result.add("trigger", identity(trigger));
         if (trigger.getTable() != null) {
            result.add("table", identity(trigger.getTable()));
         }

         String ddl = tryReadDdl(trigger, monitor);
         JsonArray reads = new JsonArray();
         JsonArray writes = new JsonArray();
         JsonArray calls = new JsonArray();
         JsonArray expandedRoutines = new JsonArray();
         boolean dynamicSql = false;
         if (ddl != null) {
            result.add("parsed_definition", TriggerDefinitionParser.parse(ddl));
            DBeaverObjectService.ScanResult referenceScan = this.scan(connection.dataSource(), 5000, 8, false);

            for (DdlReferenceExtractor.Reference reference : DdlReferenceExtractor.extract(ddl)) {
               addFlowReference(reference, null, reads, writes, calls);
               if ("calls".equals(relationshipForOperation(reference.operation()))) {
                  DBSObject target = findReference(referenceScan.objects(), reference.objectName());
                  if (target != null) {
                     String calledDdl = tryReadDdl(target, monitor);
                     if (calledDdl != null) {
                        JsonObject expanded = identity(target);
                        expanded.addProperty("called_by", reference.objectName());
                        expanded.addProperty("ddl_available", true);
                        boolean calledDynamic = DdlReferenceExtractor.hasDynamicSql(calledDdl);
                        expanded.addProperty("dynamic_sql_detected", calledDynamic);
                        expandedRoutines.add(expanded);
                        dynamicSql |= calledDynamic;

                        for (DdlReferenceExtractor.Reference nested : DdlReferenceExtractor.extract(calledDdl)) {
                           addFlowReference(nested, qualifiedName(target), reads, writes, calls);
                        }
                     }
                  }
               }
            }

            dynamicSql |= DdlReferenceExtractor.hasDynamicSql(ddl);
            result.addProperty("ddl", ddl);
            result.addProperty("dynamic_sql_detected", dynamicSql);
         }

         result.add("reads_from", reads);
         result.add("writes_to", writes);
         result.add("calls", calls);
         result.add("expanded_routines", expandedRoutines);
         JsonObject coverage = new JsonObject();
         coverage.addProperty("trigger_table", trigger.getTable() == null ? "unavailable" : "exact");
         coverage.addProperty("body", ddl == null ? "unavailable" : "exact_trigger_ddl");
         coverage.addProperty("body_references", ddl == null ? "unavailable" : "heuristic_trigger_and_called_routine_ddl_one_level");
         coverage.addProperty("timing_and_events", ddl == null ? "unavailable" : "heuristic_ddl_parse");
         result.add("coverage", coverage);
         JsonArray blindSpots = new JsonArray();
         blindSpots.add("Called routine expansion is limited to one static level; nested calls and overloaded routine resolution may be incomplete.");
         if (dynamicSql) {
            blindSpots.add("Dynamic SQL was detected; downstream effects may be missing.");
         }

         result.add("blind_spots", blindSpots);
         return result;
      } else {
         throw new IllegalArgumentException("Trigger flow requires a trigger object");
      }
   }

   private static void addFlowReference(DdlReferenceExtractor.Reference reference, String viaRoutine, JsonArray reads, JsonArray writes, JsonArray calls) {
      JsonObject item = new JsonObject();
      item.addProperty("operation", reference.operation());
      item.addProperty("object", reference.objectName());
      if (viaRoutine != null) {
         item.addProperty("via_routine", viaRoutine);
      }

      String var6 = relationshipForOperation(reference.operation());
      switch (var6) {
         case "reads":
            reads.add(item);
            break;
         case "calls":
            calls.add(item);
            break;
         default:
            writes.add(item);
      }
   }

   JsonObject explainDataChange(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      if (this.resolve(connection, arguments) instanceof DBSTable table) {
         String operation = McpJson.requiredString(arguments, "operation").toLowerCase(Locale.ENGLISH);
         if (!Set.of("insert", "update", "delete", "merge").contains(operation)) {
            throw new IllegalArgumentException("operation must be insert, update, delete, or merge");
         } else {
            VoidProgressMonitor monitor = new VoidProgressMonitor();
            JsonObject result = new JsonObject();
            result.add("table", identity(table));
            result.addProperty("operation", operation);
            JsonArray changedColumns = new JsonArray();
            McpJson.getStrings(arguments, "changed_columns").forEach(changedColumns::add);
            result.add("changed_columns", changedColumns);
            JsonArray constraints = new JsonArray();
            Collection<? extends DBSTableForeignKey> associations = castForeignKeys(table.getAssociations(monitor));
            Collection<? extends DBSEntityConstraint> tableConstraints = table.getConstraints(monitor);
            if (tableConstraints != null) {
               for (DBSEntityConstraint constraint : tableConstraints) {
                  constraints.add(constraintPayload(constraint, monitor));
               }
            }

            if (associations != null) {
               for (DBSTableForeignKey foreignKey : associations) {
                  constraints.add(constraintPayload(foreignKey, monitor));
               }
            }

            result.add("constraints_checked", constraints);
            JsonArray triggers = new JsonArray();
            List<? extends DBSTrigger> tableTriggers = table.getTriggers(monitor);
            if (tableTriggers != null) {
               for (DBSTrigger trigger : tableTriggers) {
                  JsonObject item = identity(trigger);
                  String ddl = tryReadDdl(trigger, monitor);
                  if (ddl != null) {
                     item.addProperty("ddl_available", true);
                     item.addProperty("dynamic_sql_detected", DdlReferenceExtractor.hasDynamicSql(ddl));
                  }

                  triggers.add(item);
               }
            }

            result.add("potential_triggers", triggers);
            result.addProperty("simulation_performed", false);
            JsonObject coverage = new JsonObject();
            coverage.addProperty("constraints", "exact_for_loaded_metadata");
            coverage.addProperty("trigger_selection", "conservative_all_table_triggers");
            coverage.addProperty("side_effects", "static_analysis_only");
            result.add("coverage", coverage);
            JsonArray blindSpots = new JsonArray();
            blindSpots.add("Generic metadata cannot determine which triggers match the requested operation without a database-specific adapter.");
            blindSpots.add("External side effects and application logic are not observed.");
            result.add("blind_spots", blindSpots);
            return result;
         }
      } else {
         throw new IllegalArgumentException("Data-change explanation requires a table object");
      }
   }

   JsonObject understandDatabase(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      String budget = McpJson.getString(arguments, "budget", "standard").toLowerCase(Locale.ENGLISH);

      int objectLimit = switch (budget) {
         case "light" -> 100;
         case "deep" -> 1000;
         default -> 300;
      };

      int detailLimit = switch (budget) {
         case "light" -> 10;
         case "deep" -> 100;
         default -> 30;
      };
      String pattern = McpJson.getString(arguments, "pattern", "");
      Set<String> schemas = lowerSet(McpJson.getStrings(arguments, "schemas"));
      Set<String> requestedTypes = lowerSet(McpJson.getStrings(arguments, "types"));
      DBeaverObjectService.ScanResult scan = this.scan(connection.dataSource(), objectLimit, 8, false);
      List<DBSObject> selected = scan.objects()
         .stream()
         .map(DBeaverObjectService.ScannedObject::object)
         .filter(objectx -> pattern.isBlank() || qualifiedName(objectx).toLowerCase(Locale.ENGLISH).contains(pattern.toLowerCase(Locale.ENGLISH)))
         .filter(objectx -> schemas.isEmpty() || schemas.contains(schemaName(objectx).toLowerCase(Locale.ENGLISH)))
         .filter(objectx -> requestedTypes.isEmpty() || requestedTypes.contains(objectType(objectx)))
         .filter(objectx -> Set.of("table", "view", "function", "procedure", "trigger").contains(objectType(objectx)))
         .limit(detailLimit)
         .toList();
      JsonObject result = new JsonObject();
      result.addProperty("budget", budget);
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      JsonObject inventory = new JsonObject();
      Map<String, Integer> counts = new LinkedHashMap<>();
      scan.objects().forEach(item -> counts.merge(objectType(item.object()), 1, Integer::sum));
      counts.forEach(inventory::addProperty);
      result.add("inventory", inventory);
      JsonArray objects = new JsonArray();
      JsonArray rules = new JsonArray();
      JsonArray relationships = new JsonArray();

      for (DBSObject object : selected) {
         objects.add(this.describe(connection, object, budget.equals("deep")));
         if (object instanceof DBSEntity) {
            JsonObject ruleArgs = selectorArguments(connection, object);

            for (JsonElement rule : this.businessRules(ruleArgs).getAsJsonArray("rules")) {
               rules.add(rule);
            }
         }

         JsonObject dependencyArgs = selectorArguments(connection, object);

         for (JsonElement edge : this.dependencies(dependencyArgs).getAsJsonArray("edges")) {
            relationships.add(edge);
         }
      }

      result.add("objects", objects);
      result.add("business_rules", rules);
      result.add("relationships", relationships);
      addCoverage(
         result,
         scan,
         "generic_dbeaver_model_plus_ddl_heuristics",
         List.of(
            "Only selected high-value objects are expanded within the requested budget.",
            "Dynamic SQL, external services, and application code remain outside full coverage."
         )
      );
      JsonArray nextQueries = new JsonArray();
      nextQueries.add("Use dbeaver_profile_table for data-shape and quality statistics on important tables.");
      nextQueries.add("Use dbeaver_explain_trigger_flow for each trigger affecting a critical table.");
      nextQueries.add("Use dbeaver_get_dependencies with database-specific adapters for deeper lineage.");
      result.add("next_queries", nextQueries);
      return result;
   }

   DBSObject resolve(DBeaverConnectionService.ResolvedConnection connection, JsonObject arguments) throws Exception {
      String objectId = McpJson.getString(arguments, "object_id", "");
      String qualifiedName = McpJson.getString(arguments, "qualified_name", "");
      String name = McpJson.getString(arguments, "name", "");
      String type = McpJson.getString(arguments, "object_type", "");
      if (objectId.isBlank() && qualifiedName.isBlank() && name.isBlank()) {
         throw new IllegalArgumentException("Pass object_id, qualified_name, or name");
      } else {
         DBeaverObjectService.ScanResult scan = this.scan(connection.dataSource(), 5000, 20, true);
         List<DBSObject> matches = new ArrayList<>();

         for (DBeaverObjectService.ScannedObject scanned : scan.objects()) {
            DBSObject candidate = scanned.object();
            if (type.isBlank() || type.equalsIgnoreCase(objectType(candidate))) {
               if (!objectId.isBlank() && objectId.equals(objectId(candidate))) {
                  matches.add(candidate);
               } else if (!qualifiedName.isBlank() && normalizeName(qualifiedName).equals(normalizeName(qualifiedName(candidate)))) {
                  matches.add(candidate);
               } else if (!name.isBlank() && name.equalsIgnoreCase(candidate.getName())) {
                  matches.add(candidate);
               }
            }
         }

         if (matches.isEmpty()) {
            throw new IllegalArgumentException("Database object not found for the supplied selector");
         } else if (matches.size() > 1) {
            String choices = matches.stream().limit(20L).map(DBeaverObjectService::objectId).reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Database object selector is ambiguous. Use object_id. Matches: " + choices);
         } else {
            return matches.getFirst();
         }
      }
   }

   DBeaverObjectService.ScanResult scan(DBPDataSource dataSource, int maxObjects, int maxDepth, boolean includeSystem) throws DBException {
      DBSObjectContainer root = (DBSObjectContainer)DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
      if (root == null) {
         throw new DBException("Data source does not expose a browsable structure container");
      } else {
         DBRProgressMonitor monitor = new VoidProgressMonitor();
         List<String> errors = new ArrayList<>();
         Set<Long> extensionOwnedIds = includeSystem ? Set.of() : postgreSqlExtensionObjectIds(dataSource, monitor, errors);
         Deque<DBeaverObjectService.ScannedObject> queue = new ArrayDeque<>();
         queue.add(new DBeaverObjectService.ScannedObject(root, 0));
         Set<DBSObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
         List<DBeaverObjectService.ScannedObject> result = new ArrayList<>();
         boolean truncated = false;

         while (!queue.isEmpty()) {
            DBeaverObjectService.ScannedObject current = queue.removeFirst();
            if (seen.add(current.object())) {
               boolean hidden = current.object() instanceof DBPHiddenObject hiddenObject && hiddenObject.isHidden();
               boolean system = isSystemObject(current.object());
               boolean extensionOwned = !includeSystem && isPostgreSqlExtensionOwned(current.object(), extensionOwnedIds);
               if (current.depth() <= 0 || includeSystem || !system && !extensionOwned) {
                  if (current.depth() > 0 && !hidden) {
                     result.add(current);
                     if (result.size() >= maxObjects) {
                        truncated = !queue.isEmpty();
                        break;
                     }
                  }

                  if (current.depth() < maxDepth && !hidden) {
                     for (DBSObject child : children(current.object(), monitor, errors)) {
                        if (child != null && !seen.contains(child)) {
                           queue.addLast(new DBeaverObjectService.ScannedObject(child, current.depth() + 1));
                        }
                     }
                  }
               }
            }
         }

         return new DBeaverObjectService.ScanResult(List.copyOf(result), List.copyOf(errors), truncated);
      }
   }

   JsonObject describe(DBeaverConnectionService.ResolvedConnection connection, DBSObject object, boolean includeDdl) throws Exception {
      VoidProgressMonitor monitor = new VoidProgressMonitor();
      JsonObject result = identity(object);
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      JsonArray blindSpots = new JsonArray();
      JsonObject coverage = new JsonObject();
      if (object instanceof DBSTypedObject typed) {
         result.add("type_details", typedPayload(typed));
      }

      if (object instanceof DBSEntityAttribute attribute) {
         result.addProperty("required", attribute.isRequired());
         result.addProperty("auto_generated", attribute.isAutoGenerated());
         if (attribute.getDefaultValue() != null) {
            result.addProperty("default", attribute.getDefaultValue());
         }

         result.addProperty("ordinal_position", attribute.getOrdinalPosition());
      }

      if (object instanceof DBSEntity entity) {
         try {
            JsonArray columns = new JsonArray();
            Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
            if (attributes != null) {
               attributes.stream().filter(attribute -> !isHiddenObject(attribute)).forEach(attribute -> columns.add(attributePayload(attribute)));
            }

            result.add("columns", columns);
            coverage.addProperty("columns", "exact_for_loaded_metadata");
         } catch (Exception var17) {
            coverage.addProperty("columns", "error");
            blindSpots.add("Columns: " + McpJson.safeMessage(var17));
         }

         try {
            JsonArray constraints = new JsonArray();
            Collection<? extends DBSEntityConstraint> values = entity.getConstraints(monitor);
            if (values != null) {
               for (DBSEntityConstraint constraint : values) {
                  constraints.add(constraintPayload(constraint, monitor));
               }
            }

            result.add("constraints", constraints);
            coverage.addProperty("constraints", "exact_for_loaded_metadata");
         } catch (Exception var19) {
            coverage.addProperty("constraints", "error");
            blindSpots.add("Constraints: " + McpJson.safeMessage(var19));
         }

         try {
            result.add("foreign_keys", associationPayloads(entity.getAssociations(monitor), monitor));
            result.add("referenced_by", associationPayloads(entity.getReferences(monitor), monitor));
            coverage.addProperty("foreign_keys", "exact_for_loaded_metadata");
         } catch (Exception var16) {
            coverage.addProperty("foreign_keys", "error");
            blindSpots.add("Foreign keys: " + McpJson.safeMessage(var16));
         }
      }

      if (object instanceof DBSTable table) {
         result.addProperty("is_view", table.isView());

         try {
            JsonArray indexes = new JsonArray();
            Collection<? extends DBSTableIndex> values = table.getIndexes(monitor);
            if (values != null) {
               for (DBSTableIndex index : values) {
                  indexes.add(indexPayload(index, monitor));
               }
            }

            result.add("indexes", indexes);
            coverage.addProperty("indexes", "exact_for_loaded_metadata");
         } catch (Exception var18) {
            coverage.addProperty("indexes", "error");
            blindSpots.add("Indexes: " + McpJson.safeMessage(var18));
         }

         try {
            JsonArray triggers = new JsonArray();
            List<? extends DBSTrigger> values = table.getTriggers(monitor);
            if (values != null) {
               values.forEach(trigger -> triggers.add(identity(trigger)));
            }

            result.add("triggers", triggers);
            coverage.addProperty("triggers", "exact_for_loaded_metadata");
         } catch (Exception var15) {
            coverage.addProperty("triggers", "error");
            blindSpots.add("Triggers: " + McpJson.safeMessage(var15));
         }
      }

      if (object instanceof DBSProcedure procedure) {
         result.addProperty("procedure_type", procedure.getProcedureType().name().toLowerCase(Locale.ENGLISH));
         JsonArray parameters = new JsonArray();
         Collection<? extends DBSProcedureParameter> values = procedure.getParameters(monitor);
         if (values != null) {
            for (DBSProcedureParameter parameter : values) {
               JsonObject item = identity(parameter);
               item.addProperty("parameter_kind", parameter.getParameterKind().name().toLowerCase(Locale.ENGLISH));
               item.add("type_details", typedPayload(parameter.getParameterType()));
               parameters.add(item);
            }
         }

         result.add("parameters", parameters);
         DBSTypedObject returnType = procedure.getReturnType(monitor);
         if (returnType != null) {
            result.add("return_type", typedPayload(returnType));
         }

         coverage.addProperty("routine_signature", "exact_for_loaded_metadata");
      }

      if (object instanceof DBSTrigger trigger) {
         if (trigger.getTable() != null) {
            result.add("table", identity(trigger.getTable()));
         }

         coverage.addProperty("trigger_table", trigger.getTable() == null ? "unavailable" : "exact");
         coverage.addProperty("trigger_timing_and_events", "requires_database_specific_adapter");
      }

      result.addProperty("ddl_available", object instanceof DBPScriptObject);
      if (includeDdl && object instanceof DBPScriptObject) {
         try {
            result.addProperty("ddl", readDdl(object, monitor, new JsonObject()));
            coverage.addProperty("ddl", "exact_from_dbeaver_object_definition");
         } catch (Exception var14) {
            coverage.addProperty("ddl", "error");
            blindSpots.add("DDL: " + McpJson.safeMessage(var14));
         }
      }

      result.add("coverage", coverage);
      result.add("blind_spots", blindSpots);
      return result;
   }

   static JsonObject identity(DBSObject object) {
      JsonObject result = new JsonObject();
      DBPDataSource dataSource = object.getDataSource();
      if (dataSource != null) {
         DBPDataSourceContainer container = dataSource.getContainer();
         result.addProperty("connection_id", container.getId());
         result.addProperty("project", container.getProject().getName());
      }

      result.addProperty("object_id", objectId(object));
      result.addProperty("object_type", objectType(object));
      result.addProperty("name", object.getName());
      result.addProperty("qualified_name", qualifiedName(object));
      JsonArray path = new JsonArray();

      for (DBSObject item : DBUtils.getObjectPath(object, true)) {
         if (!(item instanceof DBPDataSourceContainer) && !(item instanceof DBPDataSource)) {
            path.add(item.getName());
         }
      }

      result.add("object_path", path);
      String catalog = catalogName(object);
      String schema = schemaName(object);
      if (!catalog.isBlank()) {
         result.addProperty("catalog", catalog);
      }

      if (!schema.isBlank()) {
         result.addProperty("schema", schema);
      }

      result.addProperty("persisted", object.isPersisted());
      if (object instanceof DBPSystemObject system) {
         result.addProperty("system", system.isSystem());
      }

      if (object instanceof DBPStatefulObject stateful) {
         result.addProperty("state", stateful.getObjectState().getTitle());
      }

      if (object.getDescription() != null && !object.getDescription().isBlank()) {
         result.addProperty("description", object.getDescription());
      }

      result.addProperty("implementation_class", object.getClass().getName());
      DBSObject parent = object.getParentObject();
      if (parent != null) {
         JsonObject parentJson = new JsonObject();
         parentJson.addProperty("object_id", objectId(parent));
         parentJson.addProperty("object_type", objectType(parent));
         parentJson.addProperty("name", parent.getName());
         result.add("parent", parentJson);
      }

      return result;
   }

   static String objectType(DBSObject object) {
      if (object instanceof DBSTableIndex) {
         return "index";
      } else if (object instanceof DBSTableForeignKey) {
         return "foreign_key";
      } else if (object instanceof DBSEntityConstraint) {
         return "constraint";
      } else if (object instanceof DBSEntityAttribute) {
         return "column";
      } else if (object instanceof DBSTrigger) {
         return "trigger";
      } else if (object instanceof DBSProcedure procedure) {
         return procedure.getProcedureType() == DBSProcedureType.FUNCTION ? "function" : "procedure";
      } else if (object instanceof DBSTable table) {
         return table.isView() ? "view" : "table";
      } else if (object instanceof DBSTablePartition) {
         return "partition";
      } else if (object instanceof DBSCatalog) {
         return "catalog";
      } else if (object instanceof DBSSchema) {
         return "schema";
      } else if (object instanceof DBSEntity entity) {
         return entity.getEntityType().getId().toLowerCase(Locale.ENGLISH);
      } else {
         String simple = object.getClass().getSimpleName().toLowerCase(Locale.ENGLISH);
         if (simple.contains("sequence")) {
            return "sequence";
         } else if (simple.contains("package")) {
            return "package";
         } else if (simple.contains("synonym")) {
            return "synonym";
         } else if (simple.contains("event")) {
            return "event";
         } else if (simple.contains("job")) {
            return "job";
         } else {
            return object instanceof DBSObjectContainer ? "container" : "object";
         }
      }
   }

   static String objectId(DBSObject object) {
      try {
         return DBUtils.getObjectFullId(object);
      } catch (RuntimeException var2) {
         return qualifiedName(object);
      }
   }

   static String qualifiedName(DBSObject object) {
      if (object instanceof DBPQualifiedObject qualified) {
         try {
            return qualified.getFullyQualifiedName(DBPEvaluationContext.UI);
         } catch (RuntimeException var6) {
         }
      }

      List<String> names = new ArrayList<>();

      for (DBSObject item : DBUtils.getObjectPath(object, true)) {
         if (!(item instanceof DBPDataSourceContainer) && !(item instanceof DBPDataSource)) {
            names.add(item.getName());
         }
      }

      return String.join(".", names);
   }

   static String dmlName(DBSObject object) {
      if (object instanceof DBPQualifiedObject qualified) {
         return qualified.getFullyQualifiedName(DBPEvaluationContext.DML);
      } else {
         DBPDataSource dataSource = object.getDataSource();
         return dataSource == null ? object.getName() : DBUtils.getQuotedIdentifier(dataSource, object.getName());
      }
   }

   private static List<DBSObject> children(DBSObject object, DBRProgressMonitor monitor, List<String> errors) {
      List<DBSObject> result = new ArrayList<>();
      if (object instanceof DBSObjectContainer container) {
         try {
            tryAdd(result, container.getChildren(monitor), errors, object, "children");
         } catch (Exception var14) {
            errors.add(objectId(object) + " children: " + McpJson.safeMessage(var14));
         }
      }

      if (object instanceof DBSProcedureContainer procedures) {
         try {
            tryAdd(result, procedures.getProcedures(monitor), errors, object, "procedures");
         } catch (Exception var13) {
            errors.add(objectId(object) + " procedures: " + McpJson.safeMessage(var13));
         }
      }

      if (object instanceof DBSEntity entity) {
         try {
            tryAdd(result, entity.getAttributes(monitor), errors, object, "attributes");
         } catch (Exception var12) {
            errors.add(objectId(object) + " attributes: " + McpJson.safeMessage(var12));
         }

         try {
            tryAdd(result, entity.getConstraints(monitor), errors, object, "constraints");
         } catch (Exception var11) {
            errors.add(objectId(object) + " constraints: " + McpJson.safeMessage(var11));
         }

         try {
            tryAdd(result, entity.getAssociations(monitor), errors, object, "associations");
         } catch (Exception var10) {
            errors.add(objectId(object) + " associations: " + McpJson.safeMessage(var10));
         }

         try {
            tryAdd(result, entity.getReferences(monitor), errors, object, "references");
         } catch (Exception var9) {
            errors.add(objectId(object) + " references: " + McpJson.safeMessage(var9));
         }
      }

      if (object instanceof DBSTable table) {
         try {
            tryAdd(result, table.getIndexes(monitor), errors, object, "indexes");
         } catch (Exception var8) {
            errors.add(objectId(object) + " indexes: " + McpJson.safeMessage(var8));
         }

         try {
            tryAdd(result, table.getTriggers(monitor), errors, object, "triggers");
         } catch (Exception var7) {
            errors.add(objectId(object) + " triggers: " + McpJson.safeMessage(var7));
         }
      }

      if (object instanceof DBSProcedure procedure) {
         try {
            tryAdd(result, procedure.getParameters(monitor), errors, object, "parameters");
         } catch (Exception var6) {
            errors.add(objectId(object) + " parameters: " + McpJson.safeMessage(var6));
         }
      }

      return result;
   }

   private static void tryAdd(List<DBSObject> target, Collection<? extends DBSObject> values, List<String> errors, DBSObject owner, String kind) {
      if (values != null) {
         for (DBSObject value : values) {
            if (value != null && !isHiddenObject(value)) {
               target.add(value);
            }
         }
      }
   }

   private static Set<Long> postgreSqlExtensionObjectIds(DBPDataSource dataSource, DBRProgressMonitor monitor, List<String> errors) {
      if (!dataSource.getInfo().getDatabaseProductName().toLowerCase(Locale.ENGLISH).contains("postgres")) {
         return Set.of();
      } else {
         Set<Long> result = new LinkedHashSet<>();
         String query = "SELECT DISTINCT d.objid::bigint FROM pg_depend d JOIN pg_extension e ON e.oid = d.refobjid WHERE d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e'";

         try {
            DBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read PostgreSQL extension ownership");

            try {
               DBCStatement statement = session.prepareStatement(DBCStatementType.EXEC, query, false, false, false);

               try {
                  if (statement.executeStatement()) {
                     DBCResultSet rows = statement.openResultSet();

                     try {
                        while (rows != null && rows.nextRow()) {
                           Object value = rows.getAttributeValue(0);
                           if (value instanceof Number number) {
                              result.add(number.longValue());
                           } else if (value != null) {
                              try {
                                 result.add(Long.parseLong(String.valueOf(value)));
                              } catch (NumberFormatException var14) {
                              }
                           }
                        }
                     } catch (Throwable var15) {
                        if (rows != null) {
                           try {
                              rows.close();
                           } catch (Throwable var13) {
                              var15.addSuppressed(var13);
                           }
                        }

                        throw var15;
                     }

                     if (rows != null) {
                        rows.close();
                     }
                  }
               } catch (Throwable var16) {
                  if (statement != null) {
                     try {
                        statement.close();
                     } catch (Throwable var12) {
                        var16.addSuppressed(var12);
                     }
                  }

                  throw var16;
               }

               if (statement != null) {
                  statement.close();
               }
            } catch (Throwable var17) {
               if (session != null) {
                  try {
                     session.close();
                  } catch (Throwable var11) {
                     var17.addSuppressed(var11);
                  }
               }

               throw var17;
            }

            if (session != null) {
               session.close();
            }
         } catch (Exception var18) {
            errors.add("PostgreSQL extension ownership: " + McpJson.safeMessage(var18));
         }

         return Set.copyOf(result);
      }
   }

   private static JsonArray postgreSqlExtensions(DBPDataSource dataSource, DBRProgressMonitor monitor) {
      JsonArray result = new JsonArray();
      String query = "SELECT e.extname, e.extversion, n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace ORDER BY e.extname";

      try {
         DBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read PostgreSQL extensions");

         try {
            DBCStatement statement = session.prepareStatement(DBCStatementType.EXEC, query, false, false, false);

            try {
               if (statement.executeStatement()) {
                  DBCResultSet rows = statement.openResultSet();

                  try {
                     while (rows != null && rows.nextRow()) {
                        JsonObject extension = new JsonObject();
                        extension.addProperty("name", String.valueOf(rows.getAttributeValue(0)));
                        extension.addProperty("version", String.valueOf(rows.getAttributeValue(1)));
                        extension.addProperty("schema", String.valueOf(rows.getAttributeValue(2)));
                        result.add(extension);
                     }
                  } catch (Throwable var12) {
                     if (rows != null) {
                        try {
                           rows.close();
                        } catch (Throwable var11) {
                           var12.addSuppressed(var11);
                        }
                     }

                     throw var12;
                  }

                  if (rows != null) {
                     rows.close();
                  }
               }
            } catch (Throwable var13) {
               if (statement != null) {
                  try {
                     statement.close();
                  } catch (Throwable var10) {
                     var13.addSuppressed(var10);
                  }
               }

               throw var13;
            }

            if (statement != null) {
               statement.close();
            }
         } catch (Throwable var14) {
            if (session != null) {
               try {
                  session.close();
               } catch (Throwable var9) {
                  var14.addSuppressed(var9);
               }
            }

            throw var14;
         }

         if (session != null) {
            session.close();
         }
      } catch (Exception var15) {
         JsonObject error = new JsonObject();
         error.addProperty("error", McpJson.safeMessage(var15));
         result.add(error);
      }

      return result;
   }

   private static boolean isPostgreSqlExtensionOwned(DBSObject object, Set<Long> extensionOwnedIds) {
      return !extensionOwnedIds.isEmpty()
         && object.getClass().getName().startsWith("org.jkiss.dbeaver.ext.postgresql.")
         && object instanceof DBPObjectWithLongId identified
         && identified.getObjectId() > 0L
         && extensionOwnedIds.contains(identified.getObjectId());
   }

   private static boolean isHiddenObject(DBSObject object) {
      return object instanceof DBPHiddenObject hidden && hidden.isHidden();
   }

   private static boolean isSystemObject(DBSObject object) {
      if (object instanceof DBPSystemObject system && system.isSystem()) {
         return true;
      } else {
         for (DBSObject current = object; current != null; current = current.getParentObject()) {
            if (current instanceof DBSSchema) {
               String schema = current.getName().toLowerCase(Locale.ENGLISH);
               return schema.equals("pg_catalog")
                  || schema.equals("information_schema")
                  || schema.startsWith("pg_toast")
                  || schema.startsWith("pg_temp_")
                  || schema.startsWith("pg_toast_temp_");
            }
         }

         return false;
      }
   }

   private static JsonObject attributePayload(DBSEntityAttribute attribute) {
      JsonObject item = identity(attribute);
      item.add("type_details", typedPayload(attribute));
      item.addProperty("required", attribute.isRequired());
      item.addProperty("auto_generated", attribute.isAutoGenerated());
      item.addProperty("ordinal_position", attribute.getOrdinalPosition());
      if (attribute.getDefaultValue() != null) {
         item.addProperty("default", attribute.getDefaultValue());
      }

      return item;
   }

   private static JsonObject typedPayload(DBSTypedObject typed) {
      JsonObject item = new JsonObject();
      item.addProperty("type_name", typed.getTypeName());
      item.addProperty("full_type_name", typed.getFullTypeName());
      item.addProperty("type_id", typed.getTypeID());
      item.addProperty("data_kind", typed.getDataKind().name().toLowerCase(Locale.ENGLISH));
      if (typed.getPrecision() != null) {
         item.addProperty("precision", typed.getPrecision());
      }

      if (typed.getScale() != null) {
         item.addProperty("scale", typed.getScale());
      }

      item.addProperty("max_length", typed.getMaxLength());
      return item;
   }

   private static JsonObject constraintPayload(DBSEntityConstraint constraint, DBRProgressMonitor monitor) throws DBException {
      JsonObject item = identity(constraint);
      item.addProperty("constraint_type", constraint.getConstraintType().getId());
      item.addProperty("constraint_type_name", constraint.getConstraintType().getName());
      if (constraint instanceof DBSEntityReferrer referrer) {
         JsonArray columns = new JsonArray();
         List<? extends DBSEntityAttributeRef> refs = referrer.getAttributeReferences(monitor);
         if (refs != null) {
            for (DBSEntityAttributeRef ref : refs) {
               if (ref.getAttribute() != null) {
                  columns.add(ref.getAttribute().getName());
               }
            }
         }

         item.add("columns", columns);
      }

      if (constraint instanceof DBSTableCheckConstraint check && check.getCheckConstraintDefinition() != null) {
         item.addProperty("expression", check.getCheckConstraintDefinition());
      }

      if (constraint instanceof DBSTableForeignKey foreignKey) {
         item.addProperty("delete_rule", foreignKey.getDeleteRule().getId().toLowerCase(Locale.ENGLISH));
         item.addProperty("update_rule", foreignKey.getUpdateRule().getId().toLowerCase(Locale.ENGLISH));
         if (foreignKey.getAssociatedEntity() != null) {
            item.add("referenced_entity", identity(foreignKey.getAssociatedEntity()));
         }

         if (foreignKey.getReferencedConstraint() != null) {
            item.add("referenced_constraint", identity(foreignKey.getReferencedConstraint()));
         }
      }

      return item;
   }

   private static JsonObject indexPayload(DBSTableIndex index, DBRProgressMonitor monitor) throws DBException {
      JsonObject item = identity(index);
      item.addProperty("unique", index.isUnique());
      item.addProperty("primary", index.isPrimary());
      item.addProperty("index_type", String.valueOf(index.getIndexType()));
      JsonArray columns = new JsonArray();
      List<? extends DBSTableIndexColumn> refs = index.getAttributeReferences(monitor);
      if (refs != null) {
         for (DBSTableIndexColumn ref : refs) {
            JsonObject column = new JsonObject();
            column.addProperty("name", ref.getName());
            column.addProperty("ascending", ref.isAscending());
            column.addProperty("ordinal_position", ref.getOrdinalPosition());
            if (ref.getAttribute() != null) {
               column.addProperty("attribute", ref.getAttribute().getName());
            }

            columns.add(column);
         }
      }

      item.add("columns", columns);
      return item;
   }

   private static JsonArray associationPayloads(Collection<? extends DBSEntityAssociation> associations, DBRProgressMonitor monitor) throws DBException {
      JsonArray result = new JsonArray();
      if (associations != null) {
         for (DBSEntityAssociation association : associations) {
            result.add(constraintPayload(association, monitor));
         }
      }

      return result;
   }

   private static String readDdl(DBSObject object, DBRProgressMonitor monitor, JsonObject arguments) throws DBException {
      if (object instanceof DBPScriptObject scriptObject) {
         Map<String, Object> options = new LinkedHashMap<>();
         options.put("useFQN", McpJson.getBoolean(arguments, "fully_qualified_names", true));
         options.put("ddl.includeNestedObjects", McpJson.getBoolean(arguments, "include_nested_objects", true));
         options.put("ddl.includeComments", McpJson.getBoolean(arguments, "include_comments", true));
         options.put("ddl.includePermissions", McpJson.getBoolean(arguments, "include_permissions", false));
         options.put("ddl.includePartitions", McpJson.getBoolean(arguments, "include_partitions", true));
         return scriptObject.getObjectDefinitionText(monitor, options);
      } else {
         throw new IllegalArgumentException("Object does not expose DDL through DBeaver: " + qualifiedName(object));
      }
   }

   private static String tryReadDdl(DBSObject object, DBRProgressMonitor monitor) {
      if (object instanceof DBPScriptObject scriptObject) {
         try {
            return scriptObject.getObjectDefinitionText(monitor, DBPScriptObject.EMPTY_OPTIONS);
         } catch (Exception var4) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static JsonObject rule(String text, String sourceType, String sourceObject, String confidence) {
      JsonObject rule = new JsonObject();
      rule.addProperty("rule", text);
      rule.addProperty("source_type", sourceType);
      rule.addProperty("source_object", sourceObject);
      rule.addProperty("confidence", confidence);
      return rule;
   }

   private static JsonObject edge(DBSObject from, DBSObject to, String relationship, String source, String confidence) {
      return edge(from, to, relationship, source, confidence, null);
   }

   private static JsonObject edge(DBSObject from, DBSObject to, String relationship, String source, String confidence, String unresolvedName) {
      JsonObject edge = new JsonObject();
      if (from != null) {
         edge.add("from", identity(from));
      }

      if (to != null) {
         edge.add("to", identity(to));
      } else if (unresolvedName != null) {
         JsonObject unresolved = new JsonObject();
         unresolved.addProperty("qualified_name", unresolvedName);
         unresolved.addProperty("resolved", false);
         edge.add("to", unresolved);
      }

      edge.addProperty("relationship", relationship);
      edge.addProperty("source", source);
      edge.addProperty("confidence", confidence);
      return edge;
   }

   private static String relationshipForOperation(String operation) {
      return switch (operation) {
         case "from", "join" -> "reads";
         case "call", "execute", "exec" -> "calls";
         default -> "writes";
      };
   }

   private static DBSObject findReference(List<DBeaverObjectService.ScannedObject> objects, String reference) {
      String normalized = normalizeName(reference);
      List<DBSObject> exact = objects.stream()
         .map(DBeaverObjectService.ScannedObject::object)
         .filter(object -> normalizeName(qualifiedName(object)).equals(normalized))
         .toList();
      if (exact.size() == 1) {
         return exact.getFirst();
      } else {
         String simple = normalized.substring(normalized.lastIndexOf(46) + 1);
         List<DBSObject> bySimple = objects.stream()
            .map(DBeaverObjectService.ScannedObject::object)
            .filter(object -> normalizeName(object.getName()).equals(simple))
            .toList();
         return bySimple.size() == 1 ? bySimple.getFirst() : null;
      }
   }

   private static String normalizeName(String value) {
      return value.replace("\"", "").replace("`", "").replace("[", "").replace("]", "").replaceAll("\\s+", "").toLowerCase(Locale.ENGLISH);
   }

   private static JsonArray supportedObjectTypes(DBPDataSource dataSource) {
      JsonArray result = new JsonArray();
      if (dataSource.getInfo().getSupportedObjectTypes() != null) {
         for (DBSObjectType type : dataSource.getInfo().getSupportedObjectTypes()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", type.getTypeName());
            item.addProperty("description", type.getDescription());
            item.addProperty("class", type.getTypeClass().getName());
            result.add(item);
         }
      }

      return result;
   }

   private static boolean hasPrimaryKey(DBSTable table, DBRProgressMonitor monitor) {
      try {
         Collection<? extends DBSEntityConstraint> constraints = table.getConstraints(monitor);
         return constraints != null && constraints.stream().anyMatch(constraint -> "pk".equalsIgnoreCase(constraint.getConstraintType().getId()));
      } catch (Exception var3) {
         return false;
      }
   }

   private static String catalogName(DBSObject object) {
      for (DBSObject current = object; current != null; current = current.getParentObject()) {
         if (current instanceof DBSCatalog) {
            return current.getName();
         }
      }

      return "";
   }

   private static String schemaName(DBSObject object) {
      for (DBSObject current = object; current != null; current = current.getParentObject()) {
         if (current instanceof DBSSchema) {
            return current.getName();
         }
      }

      return "";
   }

   private static Set<String> lowerSet(List<String> values) {
      Set<String> result = new LinkedHashSet<>();
      values.forEach(value -> result.add(value.toLowerCase(Locale.ENGLISH)));
      return result;
   }

   private static JsonObject inferSemantics(DBSObject object, DBRProgressMonitor monitor) throws DBException {
      JsonObject result = new JsonObject();
      if (!(object instanceof DBSEntity entity)) {
         return result;
      } else {
         JsonArray columns = new JsonArray();
         Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
         if (attributes != null) {
            for (DBSEntityAttribute attribute : attributes) {
               if (!isHiddenObject(attribute)) {
                  String normalized = attribute.getName().toLowerCase(Locale.ENGLISH);
                  String meaning = null;
                  if (normalized.equals("created_at") || normalized.equals("created_on")) {
                     meaning = "likely creation timestamp";
                  } else if (normalized.equals("updated_at") || normalized.equals("modified_at")) {
                     meaning = "likely last-modification timestamp";
                  } else if (normalized.equals("deleted_at")) {
                     meaning = "likely soft-delete timestamp";
                  } else if (normalized.equals("tenant_id")) {
                     meaning = "likely tenant partition key";
                  } else if (normalized.equals("status") || normalized.endsWith("_status")) {
                     meaning = "likely lifecycle or state field";
                  } else if (normalized.equals("id") || normalized.endsWith("_id")) {
                     meaning = "likely identifier or relationship key";
                  }

                  if (meaning != null) {
                     JsonObject item = new JsonObject();
                     item.addProperty("column", attribute.getName());
                     item.addProperty("meaning", meaning);
                     item.addProperty("confidence", "inferred_low");
                     columns.add(item);
                  }
               }
            }
         }

         result.add("columns", columns);
         return result;
      }
   }

   private static JsonObject selectorArguments(DBeaverConnectionService.ResolvedConnection connection, DBSObject object) {
      JsonObject arguments = new JsonObject();
      arguments.addProperty("connection", connection.container().getId());
      arguments.addProperty("project", connection.container().getProject().getName());
      arguments.addProperty("object_id", objectId(object));
      arguments.addProperty("auto_connect", true);
      return arguments;
   }

   private static void addCoverage(JsonObject result, DBeaverObjectService.ScanResult scan, String metadataSource, List<String> additionalBlindSpots) {
      JsonObject coverage = new JsonObject();
      coverage.addProperty("metadata_source", metadataSource);
      coverage.addProperty("scan_complete", !scan.truncated());
      coverage.addProperty("metadata_errors", scan.errors().size());
      result.add("coverage", coverage);
      JsonArray blindSpots = new JsonArray();
      scan.errors().stream().limit(50L).forEach(blindSpots::add);
      additionalBlindSpots.forEach(blindSpots::add);
      result.add("blind_spots", blindSpots);
   }

   private static Collection<? extends DBSTableForeignKey> castForeignKeys(Collection<? extends DBSEntityAssociation> associations) {
      return associations == null ? null : associations.stream().filter(DBSTableForeignKey.class::isInstance).map(DBSTableForeignKey.class::cast).toList();
   }

   record ScanResult(List<DBeaverObjectService.ScannedObject> objects, List<String> errors, boolean truncated) {
   }

   record ScannedObject(DBSObject object, int depth) {
   }
}
