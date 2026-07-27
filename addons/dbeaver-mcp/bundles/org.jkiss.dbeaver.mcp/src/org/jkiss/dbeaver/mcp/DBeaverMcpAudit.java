/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class DBeaverMcpAudit {
   private static final int MAX_ENTRIES = 1000;
   private final Deque<Entry> entries = new ArrayDeque<>();
   private final Map<String, Counters> counters = new LinkedHashMap<>();

   synchronized void record(String tool, boolean success, long elapsedNanos, String errorType) {
      Entry entry = new Entry(
         Instant.now(),
         tool,
         success,
         elapsedNanos / 1_000_000.0,
         errorType == null ? "" : errorType
      );
      this.entries.addFirst(entry);
      while (this.entries.size() > MAX_ENTRIES) this.entries.removeLast();
      Counters counter = this.counters.computeIfAbsent(tool, ignored -> new Counters());
      counter.calls.increment();
      if (success) counter.success.increment(); else counter.failures.increment();
      counter.elapsedMicros.add(Math.max(0L, elapsedNanos / 1000L));
   }

   synchronized JsonObject list(int limit) {
      JsonArray items = new JsonArray();
      this.entries.stream().limit(Math.max(1, Math.min(limit, MAX_ENTRIES))).forEach(entry -> items.add(entry.payload()));
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.addProperty("retained", this.entries.size());
      result.add("entries", items);
      result.addProperty("privacy", "metadata_only_no_arguments_sql_results_or_credentials");
      return result;
   }

   synchronized JsonObject metrics() {
      JsonArray items = new JsonArray();
      this.counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
         Counters value = entry.getValue();
         long calls = value.calls.sum();
         JsonObject item = new JsonObject();
         item.addProperty("tool", entry.getKey());
         item.addProperty("calls", calls);
         item.addProperty("success", value.success.sum());
         item.addProperty("failures", value.failures.sum());
         item.addProperty("avg_latency_ms", calls == 0L ? 0.0 : value.elapsedMicros.sum() / 1000.0 / calls);
         items.add(item);
      });
      JsonObject result = new JsonObject();
      result.addProperty("tool_count", items.size());
      result.add("tools", items);
      return result;
   }

   synchronized JsonObject clear() {
      int removed = this.entries.size();
      this.entries.clear();
      this.counters.clear();
      JsonObject result = new JsonObject();
      result.addProperty("cleared", true);
      result.addProperty("removed_entries", removed);
      return result;
   }

   private record Entry(Instant at, String tool, boolean success, double elapsedMs, String errorType) {
      JsonObject payload() {
         JsonObject result = new JsonObject();
         result.addProperty("at", this.at.toString());
         result.addProperty("tool", this.tool);
         result.addProperty("success", this.success);
         result.addProperty("elapsed_ms", this.elapsedMs);
         if (!this.errorType.isBlank()) result.addProperty("error_type", this.errorType);
         return result;
      }
   }

   private static final class Counters {
      final LongAdder calls = new LongAdder();
      final LongAdder success = new LongAdder();
      final LongAdder failures = new LongAdder();
      final LongAdder elapsedMicros = new LongAdder();
   }
}
