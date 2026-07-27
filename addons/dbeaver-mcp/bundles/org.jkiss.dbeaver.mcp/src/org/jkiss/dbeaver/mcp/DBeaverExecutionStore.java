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
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DBeaverExecutionStore {
   static final Duration APPROVAL_TTL = Duration.ofMinutes(5L);
   private static final int MAX_PENDING_APPROVALS = 50;
   private static final int MAX_EXECUTIONS = 50;
   private static final int PREVIEW_ROWS = 20;
   private static final int MAX_CELL_PREVIEW_CHARS = 4096;
   private final Map<String, PendingExecution> pending = new LinkedHashMap<>();
   private final Map<String, StoredExecution> executions = new LinkedHashMap<>();
   private String lastExecutionId = "";

   synchronized JsonObject createApproval(ExecutionRequest request) {
      cleanup();
      while (this.pending.size() >= MAX_PENDING_APPROVALS) {
         this.pending.remove(this.pending.keySet().iterator().next());
      }
      String approvalId = UUID.randomUUID().toString();
      Instant expiresAt = Instant.now().plus(APPROVAL_TTL);
      this.pending.put(approvalId, new PendingExecution(approvalId, request, expiresAt));
      JsonObject payload = requestPayload(request);
      payload.addProperty("sql_preview", truncate(request.sql(), 4000));
      payload.addProperty("sql_chars", request.sql().length());
      payload.addProperty("sql_preview_truncated", request.sql().length() > 4000);
      payload.addProperty("approved", true);
      payload.addProperty("approval_id", approvalId);
      payload.addProperty("expires_at", expiresAt.toString());
      payload.addProperty("one_time", true);
      return payload;
   }

   synchronized ExecutionRequest consumeApproval(String approvalId) {
      cleanup();
      PendingExecution pendingExecution = this.pending.remove(approvalId);
      if (pendingExecution == null) {
         throw new IllegalArgumentException("Execution approval is missing, expired, cancelled, or already consumed");
      }
      return pendingExecution.request();
   }

   synchronized JsonObject cancelApproval(String approvalId) {
      cleanup();
      PendingExecution removed = this.pending.remove(approvalId);
      JsonObject payload = new JsonObject();
      payload.addProperty("cancelled", removed != null);
      payload.addProperty("approval_id", approvalId);
      return payload;
   }

   synchronized JsonObject storeResult(ExecutionRequest request, JsonObject rawResult) {
      cleanup();
      while (this.executions.size() >= MAX_EXECUTIONS) {
         String oldest = this.executions.keySet().iterator().next();
         this.executions.remove(oldest);
      }
      String executionId = UUID.randomUUID().toString();
      StoredExecution stored = new StoredExecution(executionId, request, rawResult.deepCopy(), Instant.now());
      this.executions.put(executionId, stored);
      this.lastExecutionId = executionId;
      return summaryPayload(stored, true);
   }

   synchronized JsonObject lastResult() {
      cleanup();
      if (this.lastExecutionId.isBlank()) {
         JsonObject payload = new JsonObject();
         payload.addProperty("available", false);
         return payload;
      }
      StoredExecution stored = this.executions.get(this.lastExecutionId);
      if (stored == null) {
         this.lastExecutionId = "";
         JsonObject payload = new JsonObject();
         payload.addProperty("available", false);
         return payload;
      }
      JsonObject payload = summaryPayload(stored, true);
      payload.addProperty("available", true);
      return payload;
   }

   synchronized JsonObject fetchResult(String requestedExecutionId, int page, int pageSize) {
      cleanup();
      String executionId = requestedExecutionId.isBlank() ? this.lastExecutionId : requestedExecutionId;
      StoredExecution stored = this.executions.get(executionId);
      if (stored == null) {
         throw new IllegalArgumentException("Execution result not found: " + (executionId.isBlank() ? "<none>" : executionId));
      }
      JsonArray allRows = rows(stored.rawResult());
      int totalRows = allRows.size();
      int from = Math.min(totalRows, (page - 1) * pageSize);
      int to = Math.min(totalRows, from + pageSize);
      JsonArray selected = new JsonArray();
      for (int index = from; index < to; index++) {
         selected.add(boundedCopy(allRows.get(index)));
      }
      JsonObject payload = summaryPayload(stored, false);
      payload.addProperty("page", page);
      payload.addProperty("page_size", pageSize);
      payload.addProperty("total_rows", totalRows);
      payload.addProperty("has_more", to < totalRows);
      payload.add("rows", selected);
      return payload;
   }

   synchronized JsonObject queryHistory(int limit) {
      cleanup();
      List<StoredExecution> values = new ArrayList<>(this.executions.values());
      JsonArray history = new JsonArray();
      for (int index = values.size() - 1; index >= 0 && history.size() < limit; index--) {
         StoredExecution stored = values.get(index);
         JsonObject item = summaryPayload(stored, false);
         item.addProperty("sql", truncate(stored.request().sql(), 4000));
         history.add(item);
      }
      JsonObject payload = new JsonObject();
      payload.addProperty("count", history.size());
      payload.add("queries", history);
      payload.addProperty("scope", "Queries executed through the DBeaver MCP operator bridge");
      return payload;
   }

   private static JsonObject requestPayload(ExecutionRequest request) {
      JsonObject payload = new JsonObject();
      payload.addProperty("editor_id", request.editorId());
      payload.addProperty("connection", request.connection());
      payload.addProperty("project", request.project());
      payload.addProperty("sql_sha256", sha256(request.sql()));
      payload.addProperty("read_only", request.readOnly());
      payload.addProperty("risk", request.readOnly() ? "read" : "write");
      payload.addProperty("max_rows", request.maxRows());
      payload.addProperty("timeout_seconds", request.timeoutSeconds());
      payload.addProperty("created_at", request.createdAt().toString());
      return payload;
   }

   private static JsonObject summaryPayload(StoredExecution stored, boolean includePreview) {
      JsonObject payload = requestPayload(stored.request());
      payload.remove("sql");
      payload.addProperty("execution_id", stored.executionId());
      payload.addProperty("executed_at", stored.executedAt().toString());
      JsonObject raw = stored.rawResult();
      copy(raw, payload, "has_result_set");
      copy(raw, payload, "row_count");
      copy(raw, payload, "update_count");
      copy(raw, payload, "truncated");
      copy(raw, payload, "elapsed_ms");
      copy(raw, payload, "warnings");
      JsonElement columns = raw.get("columns");
      payload.add("columns", columns != null ? columns.deepCopy() : new JsonArray());
      JsonArray allRows = rows(raw);
      payload.addProperty("rows_available", allRows.size());
      if (includePreview) {
         JsonArray preview = new JsonArray();
         for (int index = 0; index < Math.min(PREVIEW_ROWS, allRows.size()); index++) {
            preview.add(boundedCopy(allRows.get(index)));
         }
         payload.add("preview_rows", preview);
         payload.addProperty("preview_truncated", preview.size() < allRows.size());
      }
      return payload;
   }

   private static JsonArray rows(JsonObject raw) {
      JsonElement rows = raw.get("rows");
      return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : new JsonArray();
   }

   private static JsonElement boundedCopy(JsonElement value) {
      if (value == null || value.isJsonNull()) {
         return JsonNull.INSTANCE;
      }
      if (value.isJsonPrimitive()) {
         if (value.getAsJsonPrimitive().isString()) {
            return McpJson.GSON.toJsonTree(truncate(value.getAsString(), MAX_CELL_PREVIEW_CHARS));
         }
         return value.deepCopy();
      }
      if (value.isJsonArray()) {
         JsonArray result = new JsonArray();
         for (JsonElement item : value.getAsJsonArray()) {
            result.add(boundedCopy(item));
         }
         return result;
      }
      JsonObject result = new JsonObject();
      value.getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(), boundedCopy(entry.getValue())));
      return result;
   }

   private static void copy(JsonObject source, JsonObject target, String property) {
      JsonElement value = source.get(property);
      if (value != null) {
         target.add(property, value.deepCopy());
      }
   }

   private synchronized void cleanup() {
      Instant now = Instant.now();
      this.pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
      if (!this.lastExecutionId.isBlank() && !this.executions.containsKey(this.lastExecutionId)) {
         this.lastExecutionId = "";
      }
   }

   private static String sha256(String text) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(digest);
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 is unavailable", e);
      }
   }

   private static String truncate(String value, int maxChars) {
      return value.length() <= maxChars ? value : value.substring(0, maxChars) + "\u2026[truncated]";
   }

   record ExecutionRequest(
      String editorId,
      String connection,
      String project,
      String sql,
      int maxRows,
      int timeoutSeconds,
      boolean autoConnect,
      boolean readOnly,
      Instant createdAt
   ) {
   }

   private record PendingExecution(String approvalId, ExecutionRequest request, Instant expiresAt) {
   }

   private record StoredExecution(String executionId, ExecutionRequest request, JsonObject rawResult, Instant executedAt) {
   }
}
