/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DBeaverTestStore {
   private static final int MAX_SNAPSHOTS = 25;
   private static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
   private static final int MAX_DIFFERENCES = 200;

   private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
   private final Deque<String> order = new ArrayDeque<>();

   JsonObject capture(String name, String tool, JsonObject payload) throws Exception {
      byte[] bytes = McpJson.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_SNAPSHOT_BYTES) {
         throw new IllegalArgumentException("Snapshot exceeds the 2 MiB in-memory safety limit");
      }
      String id = "snapshot-" + UUID.randomUUID();
      Snapshot snapshot = new Snapshot(id, safeName(name), tool, Instant.now(), fingerprint(bytes), payload.deepCopy(), bytes.length);
      synchronized (this.order) {
         while (this.order.size() >= MAX_SNAPSHOTS) {
            String removed = this.order.removeLast();
            this.snapshots.remove(removed);
         }
         this.snapshots.put(id, snapshot);
         this.order.addFirst(id);
      }
      return snapshot.summary(false);
   }

   JsonObject list() {
      JsonArray items = new JsonArray();
      synchronized (this.order) {
         for (String id : this.order) {
            Snapshot snapshot = this.snapshots.get(id);
            if (snapshot != null) items.add(snapshot.summary(false));
         }
      }
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.add("snapshots", items);
      return result;
   }

   JsonObject get(String id) {
      return require(id).summary(true);
   }

   JsonObject delete(String id) {
      Snapshot removed = this.snapshots.remove(id);
      if (removed == null) throw new IllegalArgumentException("Unknown test snapshot: " + id);
      synchronized (this.order) {
         this.order.remove(id);
      }
      JsonObject result = removed.summary(false);
      result.addProperty("deleted", true);
      return result;
   }

   JsonObject compare(String leftId, String rightId) {
      Snapshot left = require(leftId);
      Snapshot right = require(rightId);
      JsonArray differences = new JsonArray();
      compareRecursive("", left.payload(), right.payload(), differences);
      JsonObject result = new JsonObject();
      result.add("left", left.summary(false));
      result.add("right", right.summary(false));
      result.addProperty("equal", differences.isEmpty());
      result.addProperty("difference_count", differences.size());
      result.addProperty("differences_truncated", differences.size() >= MAX_DIFFERENCES);
      result.add("differences", differences);
      return result;
   }

   private Snapshot require(String id) {
      Snapshot snapshot = this.snapshots.get(id);
      if (snapshot == null) throw new IllegalArgumentException("Unknown test snapshot: " + id);
      return snapshot;
   }

   private static void compareRecursive(String path, JsonElement left, JsonElement right, JsonArray differences) {
      if (differences.size() >= MAX_DIFFERENCES) return;
      if (left == null || right == null || left.isJsonNull() || right.isJsonNull()) {
         if (left == null || right == null || !left.equals(right)) addDifference(path, left, right, differences);
         return;
      }
      if (left.isJsonObject() && right.isJsonObject()) {
         Set<String> keys = new LinkedHashSet<>();
         keys.addAll(left.getAsJsonObject().keySet());
         keys.addAll(right.getAsJsonObject().keySet());
         for (String key : keys.stream().sorted().toList()) {
            compareRecursive(path + "/" + escape(key), left.getAsJsonObject().get(key), right.getAsJsonObject().get(key), differences);
            if (differences.size() >= MAX_DIFFERENCES) return;
         }
         return;
      }
      if (left.isJsonArray() && right.isJsonArray()) {
         int maximum = Math.max(left.getAsJsonArray().size(), right.getAsJsonArray().size());
         for (int index = 0; index < maximum; index++) {
            JsonElement a = index < left.getAsJsonArray().size() ? left.getAsJsonArray().get(index) : null;
            JsonElement b = index < right.getAsJsonArray().size() ? right.getAsJsonArray().get(index) : null;
            compareRecursive(path + "/" + index, a, b, differences);
            if (differences.size() >= MAX_DIFFERENCES) return;
         }
         return;
      }
      if (!left.equals(right)) addDifference(path, left, right, differences);
   }

   private static void addDifference(String path, JsonElement left, JsonElement right, JsonArray differences) {
      JsonObject difference = new JsonObject();
      difference.addProperty("path", path);
      difference.add("left", bounded(left));
      difference.add("right", bounded(right));
      differences.add(difference);
   }

   private static JsonElement bounded(JsonElement value) {
      if (value == null) return com.google.gson.JsonNull.INSTANCE;
      String json = McpJson.GSON.toJson(value);
      if (json.length() <= 2048) return value.deepCopy();
      JsonObject result = new JsonObject();
      result.addProperty("truncated", true);
      result.addProperty("preview", json.substring(0, 2048));
      return result;
   }

   private static String fingerprint(byte[] bytes) throws Exception {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   }

   private static String safeName(String value) {
      String name = value == null || value.isBlank() ? "snapshot" : value.trim();
      return name.length() <= 160 ? name : name.substring(0, 160);
   }

   private static String escape(String token) {
      return token.replace("~", "~0").replace("/", "~1");
   }

   private record Snapshot(String id, String name, String tool, Instant createdAt, String fingerprint, JsonObject payload, int bytes) {
      JsonObject summary(boolean includePayload) {
         JsonObject result = new JsonObject();
         result.addProperty("snapshot_id", this.id);
         result.addProperty("name", this.name);
         result.addProperty("tool", this.tool);
         result.addProperty("created_at", this.createdAt.toString());
         result.addProperty("fingerprint", this.fingerprint);
         result.addProperty("bytes", this.bytes);
         if (includePayload) result.add("payload", this.payload.deepCopy());
         return result;
      }
   }
}
