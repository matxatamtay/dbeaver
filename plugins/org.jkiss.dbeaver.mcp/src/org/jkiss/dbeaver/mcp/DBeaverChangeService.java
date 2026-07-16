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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.jkiss.dbeaver.model.struct.DBSObject;

final class DBeaverChangeService {
   private static final Set<String> DEFAULT_COMPARE_TYPES = Set.of("table", "view", "function", "procedure", "trigger", "sequence", "package", "type");
   private final DBeaverConnectionService connections;
   private final DBeaverObjectService objects;

   DBeaverChangeService(DBeaverConnectionService connections, DBeaverObjectService objects) {
      this.connections = connections;
      this.objects = objects;
   }

   JsonObject compareSchemas(JsonObject arguments) throws Exception {
      String leftName = McpJson.requiredString(arguments, "left_connection");
      String rightName = McpJson.requiredString(arguments, "right_connection");
      String leftProject = McpJson.getString(arguments, "left_project", "");
      String rightProject = McpJson.getString(arguments, "right_project", "");
      boolean autoConnect = McpJson.getBoolean(arguments, "auto_connect", true);
      int maxObjects = McpJson.getInt(arguments, "max_objects", 3000, 1, 10000);
      boolean includeDdl = McpJson.getBoolean(arguments, "include_ddl", true);
      String leftSchema = McpJson.getString(arguments, "left_schema", "");
      String rightSchema = McpJson.getString(arguments, "right_schema", leftSchema);
      Set<String> types = lowerSet(McpJson.getStrings(arguments, "types"));
      if (types.isEmpty()) {
         types = DEFAULT_COMPARE_TYPES;
      }

      DBeaverConnectionService.ResolvedConnection left = this.connections.resolve(leftName, leftProject, autoConnect);
      DBeaverConnectionService.ResolvedConnection right = this.connections.resolve(rightName, rightProject, autoConnect);
      DBeaverChangeService.SideSnapshot leftSnapshot = this.snapshot(left, leftSchema, types, maxObjects, includeDdl);
      DBeaverChangeService.SideSnapshot rightSnapshot = this.snapshot(right, rightSchema, types, maxObjects, includeDdl);
      JsonArray added = new JsonArray();
      JsonArray removed = new JsonArray();
      JsonArray changed = new JsonArray();
      JsonArray unchanged = new JsonArray();
      Set<String> allKeys = new LinkedHashSet<>();
      allKeys.addAll(leftSnapshot.items().keySet());
      allKeys.addAll(rightSnapshot.items().keySet());

      for (String key : allKeys.stream().sorted().toList()) {
         DBeaverChangeService.SnapshotItem leftItem = leftSnapshot.items().get(key);
         DBeaverChangeService.SnapshotItem rightItem = rightSnapshot.items().get(key);
         if (leftItem == null) {
            added.add(rightItem.summary().deepCopy());
         } else if (rightItem == null) {
            removed.add(leftItem.summary().deepCopy());
         } else if (!leftItem.fingerprint().equals(rightItem.fingerprint())) {
            JsonObject difference = new JsonObject();
            difference.addProperty("key", key);
            difference.add("left", leftItem.summary().deepCopy());
            difference.add("right", rightItem.summary().deepCopy());
            difference.addProperty("left_fingerprint", leftItem.fingerprint());
            difference.addProperty("right_fingerprint", rightItem.fingerprint());
            difference.add("differences", shallowDifferences(leftItem.comparable(), rightItem.comparable()));
            changed.add(difference);
         } else {
            unchanged.add(leftItem.summary().deepCopy());
         }
      }

      JsonObject result = new JsonObject();
      result.add("left", sidePayload(left, leftSchema, leftSnapshot));
      result.add("right", sidePayload(right, rightSchema, rightSnapshot));
      result.addProperty("added_count", added.size());
      result.addProperty("removed_count", removed.size());
      result.addProperty("changed_count", changed.size());
      result.addProperty("unchanged_count", unchanged.size());
      result.add("added", added);
      result.add("removed", removed);
      result.add("changed", changed);
      if (McpJson.getBoolean(arguments, "include_unchanged", false)) {
         result.add("unchanged", unchanged);
      }

      JsonObject coverage = new JsonObject();
      coverage.addProperty("metadata", "generic_dbeaver_model");
      coverage.addProperty("ddl", includeDdl ? "included_when_available" : "not_requested");
      coverage.addProperty("left_scan_complete", !leftSnapshot.scan().truncated());
      coverage.addProperty("right_scan_complete", !rightSnapshot.scan().truncated());
      result.add("coverage", coverage);
      JsonArray blindSpots = new JsonArray();
      blindSpots.add("Formatting and driver-specific DDL generation can create non-semantic differences.");
      blindSpots.add("Permissions, jobs, storage attributes, and database-specific objects may require a dialect adapter.");
      leftSnapshot.scan().errors().stream().limit(20L).forEach(value -> blindSpots.add("left: " + value));
      rightSnapshot.scan().errors().stream().limit(20L).forEach(value -> blindSpots.add("right: " + value));
      result.add("blind_spots", blindSpots);
      return result;
   }

   JsonObject analyzeChange(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject target = this.objects.resolve(connection, arguments);
      JsonObject change = McpJson.getObject(arguments, "change");
      String kind = McpJson.requiredString(change, "kind").toLowerCase(Locale.ENGLISH);
      JsonObject dependencyPayload = this.objects.dependencies(selector(connection, target));
      JsonArray edges = dependencyPayload.getAsJsonArray("edges");
      JsonArray affected = new JsonArray();
      Set<String> affectedIds = new LinkedHashSet<>();
      if (edges != null) {
         for (JsonElement element : edges) {
            if (element.isJsonObject()) {
               JsonObject edge = element.getAsJsonObject();

               for (String endpointName : List.of("from", "to")) {
                  JsonElement endpoint = edge.get(endpointName);
                  if (endpoint != null && endpoint.isJsonObject()) {
                     String objectId = McpJson.getString(endpoint.getAsJsonObject(), "object_id", "");
                     if (!objectId.isBlank() && !objectId.equals(DBeaverObjectService.objectId(target)) && affectedIds.add(objectId)) {
                        affected.add(endpoint.deepCopy());
                     }
                  }
               }
            }
         }
      }

      DBeaverChangeService.Risk risk = risk(kind, target, affected.size(), change);
      JsonObject result = new JsonObject();
      result.add("target", DBeaverObjectService.identity(target));
      result.add("change", change.deepCopy());
      result.addProperty("risk_level", risk.level());
      result.addProperty("risk_reason", risk.reason());
      result.addProperty("affected_object_count", affected.size());
      result.add("affected_objects", affected);
      result.add("dependency_edges", edges == null ? new JsonArray() : edges.deepCopy());
      result.add("migration_steps", migrationSteps(kind, target, change));
      result.add("rollback_concerns", rollbackConcerns(kind));
      JsonObject checks = new JsonObject();
      checks.addProperty("data_compatibility_checked", false);
      checks.addProperty("application_code_checked", false);
      checks.addProperty("database_dependencies_checked", true);
      checks.addProperty("dynamic_sql_detected", dependencyPayload.has("dynamic_sql_detected") && dependencyPayload.get("dynamic_sql_detected").getAsBoolean());
      result.add("checks", checks);
      JsonObject coverage = new JsonObject();
      coverage.addProperty("dependencies", "metadata_plus_ddl_heuristics");
      coverage.addProperty("data_compatibility", "not_executed");
      coverage.addProperty("locking_and_rewrite_cost", "generic_heuristics_only");
      result.add("coverage", coverage);
      JsonArray blindSpots = dependencyPayload.getAsJsonArray("blind_spots");
      JsonArray combined = blindSpots == null ? new JsonArray() : blindSpots.deepCopy();
      combined.add("Application queries, ORM mappings, reports, and code outside the database are not scanned.");
      combined.add("Exact lock, table rewrite, and online-DDL behavior depends on the database/version.");
      result.add("blind_spots", combined);
      return result;
   }

   private DBeaverChangeService.SideSnapshot snapshot(
      DBeaverConnectionService.ResolvedConnection connection, String schema, Set<String> types, int maxObjects, boolean includeDdl
   ) throws Exception {
      DBeaverObjectService.ScanResult scan = this.objects.scan(connection.dataSource(), maxObjects, 20, false);
      Map<String, DBeaverChangeService.SnapshotItem> items = new LinkedHashMap<>();

      for (DBeaverObjectService.ScannedObject scanned : scan.objects()) {
         DBSObject object = scanned.object();
         String type = DBeaverObjectService.objectType(object);
         if (types.contains(type)) {
            JsonObject identity = DBeaverObjectService.identity(object);
            String objectSchema = McpJson.getString(identity, "schema", "");
            if (schema.isBlank() || schema.equalsIgnoreCase(objectSchema)) {
               JsonObject description;
               try {
                  description = this.objects.describe(connection, object, includeDdl);
               } catch (Exception var17) {
                  description = identity.deepCopy();
                  description.addProperty("description_error", McpJson.safeMessage(var17));
               }

               JsonObject comparable = normalizeComparable(description);
               String key = compareKey(identity, schema);
               items.put(key, new DBeaverChangeService.SnapshotItem(identity, comparable, fingerprint(comparable)));
            }
         }
      }

      return new DBeaverChangeService.SideSnapshot(Map.copyOf(items), scan);
   }

   private static String compareKey(JsonObject identity, String selectedSchema) {
      String type = McpJson.getString(identity, "object_type", "object");
      JsonArray path = identity.getAsJsonArray("object_path");
      List<String> names = new ArrayList<>();
      if (path != null) {
         for (JsonElement element : path) {
            names.add(element.getAsString());
         }
      }

      if (!selectedSchema.isBlank()) {
         for (int index = 0; index < names.size(); index++) {
            if (selectedSchema.equalsIgnoreCase(names.get(index))) {
               names = new ArrayList<>(names.subList(index + 1, names.size()));
               break;
            }
         }
      }

      String relative = names.isEmpty() ? McpJson.getString(identity, "name", "") : String.join(".", names);
      return type + "|" + normalizeName(relative);
   }

   private static JsonObject normalizeComparable(JsonObject source) {
      JsonObject copy = source.deepCopy();
      removeVolatile(copy);
      normalizeDdl(copy);
      return copy;
   }

   private static void removeVolatile(JsonElement element) {
      if (element.isJsonObject()) {
         JsonObject object = element.getAsJsonObject();

         for (String key : List.of(
            "connection",
            "connection_id",
            "project",
            "object_id",
            "implementation_class",
            "coverage",
            "blind_spots",
            "connected_by_tool",
            "elapsed_ms",
            "state"
         )) {
            object.remove(key);
         }

         for (Entry<String, JsonElement> entry : new ArrayList<Entry<String, JsonElement>>(object.entrySet())) {
            removeVolatile(entry.getValue());
         }
      } else if (element.isJsonArray()) {
         for (JsonElement child : element.getAsJsonArray()) {
            removeVolatile(child);
         }
      }
   }

   private static void normalizeDdl(JsonElement element) {
      if (element.isJsonObject()) {
         JsonObject object = element.getAsJsonObject();
         if (object.has("ddl") && object.get("ddl").isJsonPrimitive()) {
            object.addProperty("ddl", object.get("ddl").getAsString().replaceAll("\\s+", " ").trim());
         }

         for (Entry<String, JsonElement> entry : object.entrySet()) {
            normalizeDdl(entry.getValue());
         }
      } else if (element.isJsonArray()) {
         for (JsonElement child : element.getAsJsonArray()) {
            normalizeDdl(child);
         }
      }
   }

   private static String fingerprint(JsonObject object) throws Exception {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(McpJson.GSON.toJson(object).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
   }

   private static JsonArray shallowDifferences(JsonObject left, JsonObject right) {
      Set<String> keys = new LinkedHashSet<>();
      keys.addAll(left.keySet());
      keys.addAll(right.keySet());
      JsonArray result = new JsonArray();

      for (String key : keys.stream().sorted(Comparator.naturalOrder()).toList()) {
         JsonElement a = left.get(key);
         JsonElement b = right.get(key);
         if (a == null || b == null || !a.equals(b)) {
            JsonObject difference = new JsonObject();
            difference.addProperty("field", key);
            difference.add("left", a == null ? nullValue() : a.deepCopy());
            difference.add("right", b == null ? nullValue() : b.deepCopy());
            result.add(difference);
         }
      }

      return result;
   }

   private static JsonElement nullValue() {
      return JsonNull.INSTANCE;
   }

   private static JsonObject sidePayload(DBeaverConnectionService.ResolvedConnection connection, String schema, DBeaverChangeService.SideSnapshot snapshot) {
      JsonObject result = new JsonObject();
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      if (!schema.isBlank()) {
         result.addProperty("schema", schema);
      }

      result.addProperty("objects", snapshot.items().size());
      result.addProperty("scan_truncated", snapshot.scan().truncated());
      return result;
   }

   private static JsonObject selector(DBeaverConnectionService.ResolvedConnection connection, DBSObject target) {
      JsonObject result = new JsonObject();
      result.addProperty("connection", connection.container().getId());
      result.addProperty("project", connection.container().getProject().getName());
      result.addProperty("object_id", DBeaverObjectService.objectId(target));
      return result;
   }

   private static DBeaverChangeService.Risk risk(String kind, DBSObject target, int affectedCount, JsonObject change) {
      String type = DBeaverObjectService.objectType(target);

      return switch (kind) {
         case "drop", "drop_column", "drop_table", "drop_function", "drop_trigger" -> new DBeaverChangeService.Risk(
            "critical", "Dropping a database object is destructive and may break " + affectedCount + " discovered dependents."
         );
         case "rename", "change_type", "alter_type", "set_not_null" -> new DBeaverChangeService.Risk(
            "high", "The change can invalidate stored code, queries, constraints, or existing data."
         );
         case "remove_not_null", "change_default", "drop_default", "add_constraint" -> new DBeaverChangeService.Risk(
            "medium", "The change modifies database-enforced behavior and should be regression tested."
         );
         case "add_index" -> new DBeaverChangeService.Risk(
            "medium", "Index creation can consume storage and lock or load the table, depending on database/version."
         );
         case "add_column" -> new DBeaverChangeService.Risk(
            McpJson.getBoolean(change, "required", false) ? "high" : "low",
            McpJson.getBoolean(change, "required", false)
               ? "Adding a required column may fail or rewrite existing rows unless a compatible default/backfill exists."
               : "Adding a nullable column is usually additive, but application compatibility still matters."
         );
         default -> new DBeaverChangeService.Risk(
            affectedCount > 0 ? "medium" : "low", "Generic impact analysis found " + affectedCount + " related objects for " + type + "."
         );
      };
   }

   private static JsonArray migrationSteps(String kind, DBSObject target, JsonObject change) {
      JsonArray steps = new JsonArray();
      steps.add("Capture a fresh schema snapshot and verify database-specific DDL semantics.");
      if (Set.of("change_type", "alter_type", "set_not_null", "add_constraint").contains(kind)) {
         steps.add("Profile existing values and identify rows incompatible with the proposed rule.");
         steps.add("Backfill or clean incompatible data before enforcing the schema change.");
      }

      if (kind.startsWith("drop") || kind.equals("rename")) {
         steps.add("Update discovered dependent views, routines, triggers, and foreign keys in dependency order.");
      }

      steps.add("Apply the change in a non-production environment and rerun schema comparison plus business-flow tests.");
      steps.add("Prepare an explicit rollback or forward-fix script before production rollout.");
      return steps;
   }

   private static JsonArray rollbackConcerns(String kind) {
      JsonArray concerns = new JsonArray();
      if (kind.startsWith("drop")) {
         concerns.add("Dropped data or source definitions may require backup restoration and may not be transactionally recoverable.");
      }

      if (Set.of("change_type", "alter_type").contains(kind)) {
         concerns.add("Reverse conversion may be lossy after writes occur in the new type.");
      }

      if (kind.equals("rename")) {
         concerns.add("Old application versions may continue referencing the previous object name.");
      }

      concerns.add("DDL transaction and implicit-commit behavior varies by database.");
      return concerns;
   }

   private static Set<String> lowerSet(List<String> values) {
      Set<String> result = new LinkedHashSet<>();
      values.forEach(value -> result.add(value.toLowerCase(Locale.ENGLISH)));
      return result;
   }

   private static String normalizeName(String value) {
      return value.replace("\"", "").replace("`", "").replace("[", "").replace("]", "").replaceAll("\\s+", "").toLowerCase(Locale.ENGLISH);
   }

   private record Risk(String level, String reason) {
   }

   private record SideSnapshot(Map<String, DBeaverChangeService.SnapshotItem> items, DBeaverObjectService.ScanResult scan) {
   }

   private record SnapshotItem(JsonObject summary, JsonObject comparable, String fingerprint) {
   }
}
