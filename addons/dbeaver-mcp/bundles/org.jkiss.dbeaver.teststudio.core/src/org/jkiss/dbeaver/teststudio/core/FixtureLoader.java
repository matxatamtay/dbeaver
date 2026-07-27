package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.core.resources.*;

final class FixtureLoader {
   JsonArray load(String project, JsonObject config) throws Exception {
      if (config.has("rows")) {
         if (!config.get("rows").isJsonArray()) throw new IllegalArgumentException("fixture rows must be an array");
         return config.getAsJsonArray("rows").deepCopy();
      }
      String path = StudioJson.required(config, "path");
      if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
         throw new IllegalArgumentException("Fixture path must be relative and may not traverse directories");
      }
      IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(project);
      if (!p.exists() || !p.isOpen()) throw new IllegalArgumentException("Open project not found: " + project);
      IFile file = p.getFolder("Test Studio").getFolder("Fixtures").getFile(path);
      if (!file.exists()) throw new IllegalArgumentException("Fixture not found: " + file.getProjectRelativePath());
      if (file.getLocation() == null || java.nio.file.Files.isSymbolicLink(file.getLocation().toFile().toPath())) {
         throw new IllegalArgumentException("Fixture symbolic links are not allowed");
      }
      long size = file.getLocation().toFile().length();
      if (size > 10L * 1024 * 1024) throw new IllegalArgumentException("Fixture exceeds 10 MiB");
      try (var input = file.getContents()) {
         String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
         String format = StudioJson.string(config, "format", extension(path));
         return switch (format.toLowerCase(Locale.ENGLISH)) {
            case "json" -> parseJson(text);
            case "csv" -> parseCsv(text, StudioJson.integer(config, "max_rows", 10000, 1, 10000));
            default -> throw new IllegalArgumentException("Unsupported fixture format: " + format);
         };
      }
   }

   private static JsonArray parseJson(String text) {
      JsonElement value = JsonParser.parseString(text);
      if (!value.isJsonArray()) throw new IllegalArgumentException("JSON fixture root must be an array");
      if (value.getAsJsonArray().size() > 10000) throw new IllegalArgumentException("JSON fixture exceeds 10,000 rows");
      return value.getAsJsonArray();
   }

   private static JsonArray parseCsv(String text, int maxRows) {
      List<List<String>> records = csv(text);
      JsonArray rows = new JsonArray();
      if (records.isEmpty()) return rows;
      List<String> header = records.getFirst();
      if (header.isEmpty() || header.size() > 500) throw new IllegalArgumentException("CSV fixture has invalid header width");
      for (int rowIndex = 1; rowIndex < records.size() && rows.size() < maxRows; rowIndex++) {
         List<String> record = records.get(rowIndex);
         JsonObject row = new JsonObject();
         for (int column = 0; column < header.size(); column++) {
            String name = header.get(column).isBlank() ? "column_" + (column + 1) : header.get(column);
            row.addProperty(name, column < record.size() ? record.get(column) : "");
         }
         rows.add(row);
      }
      if (records.size() - 1 > maxRows) throw new IllegalArgumentException("CSV fixture exceeds configured max_rows");
      return rows;
   }

   private static List<List<String>> csv(String text) {
      List<List<String>> rows = new ArrayList<>();
      List<String> row = new ArrayList<>();
      StringBuilder cell = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < text.length(); i++) {
         char ch = text.charAt(i);
         if (quoted) {
            if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
            else if (ch == '"') quoted = false;
            else cell.append(ch);
         } else if (ch == '"') quoted = true;
         else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
         else if (ch == '\n') { row.add(trimCr(cell)); cell.setLength(0); rows.add(row); row = new ArrayList<>(); }
         else cell.append(ch);
      }
      if (quoted) throw new IllegalArgumentException("Unterminated quoted CSV value");
      if (!row.isEmpty() || cell.length() > 0) { row.add(trimCr(cell)); rows.add(row); }
      return rows;
   }

   private static String trimCr(StringBuilder value) {
      int length = value.length();
      return length > 0 && value.charAt(length - 1) == '\r' ? value.substring(0, length - 1) : value.toString();
   }

   private static String extension(String path) {
      int dot = path.lastIndexOf('.');
      return dot < 0 ? "" : path.substring(dot + 1);
   }
}
