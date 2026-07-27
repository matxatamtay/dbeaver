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
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jkiss.dbeaver.model.struct.DBSObject;

final class DBeaverGraphService {
   private final DBeaverConnectionService connections;
   private final DBeaverObjectService objects;

   DBeaverGraphService(DBeaverConnectionService connections, DBeaverObjectService objects) {
      this.connections = connections;
      this.objects = objects;
   }

   JsonObject traceLineage(JsonObject arguments) throws Exception {
      return this.trace(arguments, Set.of());
   }

   JsonObject callGraph(JsonObject arguments) throws Exception {
      JsonObject result = this.trace(arguments, Set.of("calls", "reads", "writes", "has_trigger", "fires_on"));
      result.addProperty("graph_kind", "routine_and_trigger_call_graph");
      JsonArray blindSpots = result.getAsJsonArray("blind_spots");
      blindSpots.add("Calls performed through dynamic SQL, reflection-like database features, or external languages may be missing.");
      return result;
   }

   private JsonObject trace(JsonObject arguments, Set<String> allowedRelationships) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject root = this.objects.resolve(connection, arguments);
      int maxDepth = McpJson.getInt(arguments, "max_depth", 4, 1, 12);
      int maxNodes = McpJson.getInt(arguments, "max_nodes", 200, 1, 2000);
      String direction = McpJson.getString(arguments, "direction", "both").toLowerCase(Locale.ENGLISH);
      if (!Set.of("upstream", "downstream", "both").contains(direction)) {
         throw new IllegalArgumentException("direction must be upstream, downstream, or both");
      } else {
         Set<String> requestedRelationships = new LinkedHashSet<>();
         McpJson.getStrings(arguments, "relationships").forEach(value -> requestedRelationships.add(value.toLowerCase(Locale.ENGLISH)));
         if (!allowedRelationships.isEmpty()) {
            if (requestedRelationships.isEmpty()) {
               requestedRelationships.addAll(allowedRelationships);
            } else {
               requestedRelationships.retainAll(allowedRelationships);
            }
         }

         DBeaverObjectService.ScanResult scan = this.objects.scan(connection.dataSource(), Math.min(10000, Math.max(maxNodes * 20, 1000)), 20, true);
         Map<String, DBSObject> objectIndex = new LinkedHashMap<>();

         for (DBeaverObjectService.ScannedObject scanned : scan.objects()) {
            objectIndex.put(DBeaverObjectService.objectId(scanned.object()), scanned.object());
         }

         objectIndex.put(DBeaverObjectService.objectId(root), root);
         Deque<DBeaverGraphService.NodeDepth> queue = new ArrayDeque<>();
         queue.add(new DBeaverGraphService.NodeDepth(root, 0));
         Map<String, JsonObject> nodes = new LinkedHashMap<>();
         Map<String, JsonObject> edges = new LinkedHashMap<>();
         Set<String> expanded = new LinkedHashSet<>();
         JsonArray cycles = new JsonArray();
         JsonArray unresolved = new JsonArray();
         boolean truncated = false;

         while (!queue.isEmpty()) {
            DBeaverGraphService.NodeDepth current = queue.removeFirst();
            String currentId = DBeaverObjectService.objectId(current.object());
            nodes.putIfAbsent(currentId, node(current.object(), current.depth()));
            if (nodes.size() >= maxNodes) {
               truncated = !queue.isEmpty();
               break;
            }

            if (current.depth() < maxDepth && expanded.add(currentId)) {
               for (JsonElement edgeElement : this.objects.dependencies(connection, current.object(), scan).getAsJsonArray("edges")) {
                  if (edgeElement.isJsonObject()) {
                     JsonObject edge = edgeElement.getAsJsonObject();
                     String relationship = McpJson.getString(edge, "relationship", "unknown").toLowerCase(Locale.ENGLISH);
                     if (requestedRelationships.isEmpty() || requestedRelationships.contains(relationship)) {
                        String fromId = endpointId(edge.get("from"));
                        String toId = endpointId(edge.get("to"));
                        boolean outgoing = currentId.equals(fromId);
                        boolean incoming = currentId.equals(toId);
                        if ((!direction.equals("downstream") || outgoing) && (!direction.equals("upstream") || incoming) && (outgoing || incoming)) {
                           String edgeKey = edgeKey(edge, fromId, toId);
                           edges.putIfAbsent(edgeKey, edge.deepCopy());
                           String nextId = outgoing ? toId : fromId;
                           if (nextId != null && !nextId.isBlank()) {
                              DBSObject next = objectIndex.get(nextId);
                              if (next == null) {
                                 unresolved.add(edge.deepCopy());
                              } else {
                                 int nextDepth = current.depth() + 1;
                                 nodes.putIfAbsent(nextId, node(next, nextDepth));
                                 if (expanded.contains(nextId)) {
                                    JsonObject cycle = new JsonObject();
                                    cycle.addProperty("from", currentId);
                                    cycle.addProperty("to", nextId);
                                    cycle.addProperty("relationship", relationship);
                                    cycles.add(cycle);
                                 } else if (nodes.size() < maxNodes) {
                                    queue.addLast(new DBeaverGraphService.NodeDepth(next, nextDepth));
                                 } else {
                                    truncated = true;
                                 }
                              }
                           } else {
                              unresolved.add(edge.deepCopy());
                           }
                        }
                     }
                  }
               }
            }
         }

         JsonObject result = new JsonObject();
         result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
         result.add("root", DBeaverObjectService.identity(root));
         result.addProperty("direction", direction);
         result.addProperty("max_depth", maxDepth);
         result.addProperty("max_nodes", maxNodes);
         result.addProperty("truncated", truncated || scan.truncated());
         JsonArray nodeArray = new JsonArray();
         nodes.values().forEach(nodeArray::add);
         JsonArray edgeArray = new JsonArray();
         edges.values().forEach(edgeArray::add);
         result.add("nodes", nodeArray);
         result.add("edges", edgeArray);
         result.add("cycles", cycles);
         result.add("unresolved_references", unresolved);
         JsonObject coverage = new JsonObject();
         coverage.addProperty("structural_edges", "exact_for_loaded_metadata");
         coverage.addProperty("ddl_edges", "heuristic");
         coverage.addProperty("scan_complete", !scan.truncated());
         result.add("coverage", coverage);
         JsonArray blindSpots = new JsonArray();
         blindSpots.add("Column-level lineage is not guaranteed by the generic adapter.");
         blindSpots.add("Dynamic SQL and application-level data movement may be missing.");
         scan.errors().stream().limit(25L).forEach(blindSpots::add);
         result.add("blind_spots", blindSpots);
         return result;
      }
   }

   private static JsonObject node(DBSObject object, int depth) {
      JsonObject node = DBeaverObjectService.identity(object);
      node.addProperty("depth", depth);
      return node;
   }

   private static JsonObject selector(DBeaverConnectionService.ResolvedConnection connection, DBSObject object) {
      JsonObject selector = new JsonObject();
      selector.addProperty("connection", connection.container().getId());
      selector.addProperty("project", connection.container().getProject().getName());
      selector.addProperty("object_id", DBeaverObjectService.objectId(object));
      return selector;
   }

   private static String endpointId(JsonElement endpoint) {
      return endpoint != null && endpoint.isJsonObject() ? McpJson.getString(endpoint.getAsJsonObject(), "object_id", "") : null;
   }

   private static String edgeKey(JsonObject edge, String fromId, String toId) {
      return fromId + "\u0000" + toId + "\u0000" + McpJson.getString(edge, "relationship", "") + "\u0000" + McpJson.getString(edge, "source", "");
   }

   private record NodeDepth(DBSObject object, int depth) {
   }
}
