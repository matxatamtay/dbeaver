/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DBeaverTransferService {
   private static final int MAX_EXPORT_ROWS = 10_000;
   private static final int MAX_IMPORT_ROWS = 10_000;
   private static final long MAX_EXPORT_BYTES = 50L * 1024L * 1024L;
   private static final long MAX_IMPORT_BYTES = 50L * 1024L * 1024L;

   private final DBeaverDataEditorService dataEditors;
   private final DBeaverMcpJobManager jobs;
   private final DBeaverTransferPathPolicy paths;

   DBeaverTransferService(DBeaverDataEditorService dataEditors, DBeaverMcpJobManager jobs) throws IOException {
      this.dataEditors = dataEditors;
      this.jobs = jobs;
      this.paths = new DBeaverTransferPathPolicy();
   }

   JsonObject planExport(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String format = exportFormat(arguments);
      int maxRows = McpJson.getInt(arguments, "max_rows", 10_000, 1, MAX_EXPORT_ROWS);
      boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
      Path output = this.paths.resolveOutput(McpJson.requiredString(arguments, "path"));
      DBeaverDataEditorService.TransferState state = this.dataEditors.transferState(editorId);
      JsonObject result = new JsonObject();
      result.addProperty("operation", "export");
      result.addProperty("editor_id", editorId);
      result.addProperty("format", format);
      result.addProperty("path", output.toString());
      result.addProperty("max_rows", maxRows);
      result.addProperty("max_bytes", MAX_EXPORT_BYTES);
      result.addProperty("loaded_columns", state.columns());
      result.addProperty("loaded_rows", state.loadedRows());
      result.addProperty("ordinary_sensitive_values_masked", maskSensitive);
      result.addProperty("always_mask_categories_enforced", true);
      result.addProperty("transfer_root", this.paths.root().toString());
      result.addProperty("writes_database", false);
      result.addProperty("requires_confirmation", true);
      if ("sql".equals(format)) {
         result.addProperty("portability", "best_effort_insert_statements");
      }
      return result;
   }

   JsonObject runExport(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) {
         throw new IllegalArgumentException("confirm=true is required to write an export file");
      }
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String format = exportFormat(arguments);
      int maxRows = McpJson.getInt(arguments, "max_rows", 10_000, 1, MAX_EXPORT_ROWS);
      boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
      boolean overwrite = McpJson.getBoolean(arguments, "overwrite", false);
      Path output = this.paths.resolveOutput(McpJson.requiredString(arguments, "path"));
      if (Files.exists(output) && !overwrite) {
         throw new IllegalArgumentException("Export file already exists; pass overwrite=true: " + output);
      }
      String jobId = this.jobs.submit("data-workflows", "export-" + format, true, context -> {
         context.checkCancelled();
         DBeaverDataEditorService.TransferSnapshot snapshot = this.dataEditors.snapshotTransfer(
            editorId,
            maxRows,
            MAX_EXPORT_BYTES,
            maskSensitive
         );
         context.checkCancelled();
         writeExport(output, format, snapshot.columns(), snapshot.rows(), snapshot.tableName());
         long writtenBytes = Files.size(output);
         if (writtenBytes > MAX_EXPORT_BYTES) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("Export exceeded the 50 MiB output limit");
         }
         JsonObject result = new JsonObject();
         result.addProperty("path", output.toString());
         result.addProperty("format", format);
         result.addProperty("rows", snapshot.rows().size());
         result.addProperty("columns", snapshot.columns().size());
         result.addProperty("bytes", writtenBytes);
         result.addProperty("estimated_snapshot_bytes", snapshot.estimatedBytes());
         result.addProperty("truncated", snapshot.truncated());
         result.addProperty("truncated_by_rows", snapshot.truncatedByRows());
         result.addProperty("truncated_by_bytes", snapshot.truncatedByBytes());
         result.addProperty("ordinary_sensitive_values_masked", maskSensitive);
         result.addProperty("always_mask_categories_enforced", true);
         return result;
      });
      return jobPayload(jobId, "export", format, output);
   }

   JsonObject planImport(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String format = importFormat(arguments);
      int maxRows = McpJson.getInt(arguments, "max_rows", 1_000, 1, MAX_IMPORT_ROWS);
      Path input = this.paths.resolveInput(McpJson.requiredString(arguments, "path"));
      requireImportSize(input);
      requireImportSize(input);
      JsonObject result = new JsonObject();
      result.addProperty("operation", "import_stage");
      result.addProperty("editor_id", editorId);
      result.addProperty("format", format);
      result.addProperty("path", input.toString());
      result.addProperty("bytes", Files.size(input));
      result.addProperty("max_bytes", MAX_IMPORT_BYTES);
      result.addProperty("max_rows", maxRows);
      result.addProperty("transfer_root", this.paths.root().toString());
      result.addProperty("writes_database", false);
      result.addProperty("stages_native_data_editor_rows", true);
      result.addProperty("save_required_after_import", true);
      result.addProperty("requires_confirmation", true);
      return result;
   }

   JsonObject runImport(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm_stage", false)) {
         throw new IllegalArgumentException("confirm_stage=true is required before staging imported rows");
      }
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String format = importFormat(arguments);
      int maxRows = McpJson.getInt(arguments, "max_rows", 1_000, 1, MAX_IMPORT_ROWS);
      Path input = this.paths.resolveInput(McpJson.requiredString(arguments, "path"));
      String jobId = this.jobs.submit("data-workflows", "import-stage-" + format, true, context -> {
         context.checkCancelled();
         List<Map<String, Object>> rows = readImport(input, format, maxRows);
         context.checkCancelled();
         int staged = this.dataEditors.stageRows(editorId, rows, context);
         JsonObject result = new JsonObject();
         result.addProperty("path", input.toString());
         result.addProperty("format", format);
         result.addProperty("rows_read", rows.size());
         result.addProperty("rows_staged", staged);
         result.addProperty("database_saved", false);
         result.addProperty("next_action", "dbeaver_data action=pending_changes, then save_changes with confirm=true");
         return result;
      });
      return jobPayload(jobId, "import_stage", format, input);
   }

   private static void writeExport(
      Path output,
      String format,
      List<String> columns,
      List<Map<String, Object>> rows,
      String tableName
   ) throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(
         output,
         StandardCharsets.UTF_8,
         StandardOpenOption.CREATE,
         StandardOpenOption.TRUNCATE_EXISTING,
         StandardOpenOption.WRITE
      )) {
         switch (format) {
            case "csv" -> DBeaverCsvCodec.write(writer, columns, rows);
            case "json" -> McpJson.GSON.toJson(rows, writer);
            case "sql" -> writeSql(writer, tableName, columns, rows);
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
         }
      }
   }

   private static List<Map<String, Object>> readImport(Path input, String format, int maxRows) throws IOException {
      try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
         return switch (format) {
            case "csv" -> DBeaverCsvCodec.read(reader, maxRows);
            case "json" -> readJson(reader, maxRows);
            default -> throw new IllegalArgumentException("Unsupported import format: " + format);
         };
      }
   }

   private static List<Map<String, Object>> readJson(BufferedReader reader, int maxRows) {
      JsonElement parsed = JsonParser.parseReader(reader);
      JsonArray array;
      if (parsed.isJsonArray()) {
         array = parsed.getAsJsonArray();
      } else if (parsed.isJsonObject() && parsed.getAsJsonObject().has("rows") && parsed.getAsJsonObject().get("rows").isJsonArray()) {
         array = parsed.getAsJsonObject().getAsJsonArray("rows");
      } else {
         throw new IllegalArgumentException("JSON import must be an array of objects or an object containing a rows array");
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (JsonElement element : array) {
         if (rows.size() >= maxRows) {
            break;
         }
         if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Every JSON import row must be an object");
         }
         Map<String, Object> row = new LinkedHashMap<>();
         for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            row.put(entry.getKey(), McpJson.GSON.fromJson(entry.getValue(), Object.class));
         }
         rows.add(row);
      }
      return List.copyOf(rows);
   }

   private static void writeSql(BufferedWriter writer, String tableName, List<String> columns, List<Map<String, Object>> rows) throws IOException {
      String columnList = columns.stream().map(DBeaverTransferService::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("");
      for (Map<String, Object> row : rows) {
         writer.write("INSERT INTO ");
         writer.write(tableName);
         writer.write(" (");
         writer.write(columnList);
         writer.write(") VALUES (");
         for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
               writer.write(", ");
            }
            writer.write(sqlLiteral(row.get(columns.get(index))));
         }
         writer.write(");");
         writer.newLine();
      }
   }

   private static String sqlLiteral(Object value) {
      if (value == null) {
         return "NULL";
      }
      if (value instanceof Number || value instanceof Boolean) {
         return String.valueOf(value);
      }
      return "'" + String.valueOf(value).replace("'", "''") + "'";
   }

   private static String quoteIdentifier(String value) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
   }

   private static String exportFormat(JsonObject arguments) {
      String format = McpJson.getString(arguments, "format", "csv").toLowerCase(Locale.ENGLISH);
      if (!List.of("csv", "json", "sql").contains(format)) {
         throw new IllegalArgumentException("Export format must be csv, json, or sql");
      }
      return format;
   }

   private static String importFormat(JsonObject arguments) {
      String format = McpJson.getString(arguments, "format", "csv").toLowerCase(Locale.ENGLISH);
      if (!List.of("csv", "json").contains(format)) {
         throw new IllegalArgumentException("Import format must be csv or json");
      }
      return format;
   }

   private static JsonObject jobPayload(String jobId, String operation, String format, Path path) {
      JsonObject result = new JsonObject();
      result.addProperty("job_id", jobId);
      result.addProperty("state", "queued");
      result.addProperty("operation", operation);
      result.addProperty("format", format);
      result.addProperty("path", path.toString());
      result.addProperty("status_tool", "dbeaver_job");
      return result;
   }

   private static void requireImportSize(Path input) throws IOException {
      long bytes = Files.size(input);
      if (bytes > MAX_IMPORT_BYTES) {
         throw new IllegalArgumentException("Import file exceeds the 50 MiB limit: " + bytes + " bytes");
      }
   }
}
