package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StudioJson {
   static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
   private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");
   private StudioJson() { }

   static JsonObject object(JsonObject parent, String name) {
      JsonElement value = parent.get(name);
      return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
   }
   static JsonArray array(JsonObject parent, String name) {
      JsonElement value = parent.get(name);
      return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
   }
   static String string(JsonObject parent, String name, String fallback) {
      JsonElement value = parent.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
   }
   static String required(JsonObject parent, String name) {
      String value = string(parent, name, "").trim();
      if (value.isEmpty()) throw new IllegalArgumentException("Missing required string: " + name);
      return value;
   }
   static boolean bool(JsonObject parent, String name, boolean fallback) {
      JsonElement value = parent.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
   }
   static int integer(JsonObject parent, String name, int fallback, int minimum, int maximum) {
      JsonElement value = parent.get(name);
      if (value == null || !value.isJsonPrimitive()) return fallback;
      int parsed = value.getAsInt();
      if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
      return parsed;
   }
   static JsonObject parseObject(String json) {
      JsonElement value = JsonParser.parseString(json);
      if (!value.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object");
      return value.getAsJsonObject();
   }
   static String fingerprint(JsonElement value) {
      try {
         byte[] bytes = GSON.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8);
         return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
   }
   static JsonElement canonical(JsonElement value) {
      if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value == null ? JsonNull.INSTANCE : value.deepCopy();
      if (value.isJsonArray()) {
         JsonArray result = new JsonArray();
         for (JsonElement item : value.getAsJsonArray()) result.add(canonical(item));
         return result;
      }
      JsonObject result = new JsonObject();
      value.getAsJsonObject().keySet().stream().sorted().forEach(key -> result.add(key, canonical(value.getAsJsonObject().get(key))));
      return result;
   }
   static JsonElement substitute(JsonElement value, JsonObject variables) {
      if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
      if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
         String input = value.getAsString();
         Matcher matcher = VARIABLE.matcher(input);
         StringBuffer out = new StringBuffer();
         while (matcher.find()) {
            JsonElement replacement = pointer(variables, matcher.group(1).replace('.', '/'));
            String text = replacement == null || replacement.isJsonNull() ? "" : replacement.isJsonPrimitive() ? replacement.getAsString() : GSON.toJson(replacement);
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
         }
         matcher.appendTail(out);
         return new JsonPrimitive(out.toString());
      }
      if (value.isJsonArray()) {
         JsonArray result = new JsonArray(); for (JsonElement item : value.getAsJsonArray()) result.add(substitute(item, variables)); return result;
      }
      if (value.isJsonObject()) {
         JsonObject result = new JsonObject(); value.getAsJsonObject().entrySet().forEach(e -> result.add(e.getKey(), substitute(e.getValue(), variables))); return result;
      }
      return value.deepCopy();
   }
   static JsonElement pointer(JsonElement root, String path) {
      if (path == null || path.isBlank()) return root;
      JsonElement current = root;
      for (String token : path.replace('.', '/').split("/")) {
         if (token.isEmpty()) continue;
         if (current == null || current.isJsonNull()) return null;
         if (current.isJsonObject()) current = current.getAsJsonObject().get(token);
         else if (current.isJsonArray()) {
            try { current = current.getAsJsonArray().get(Integer.parseInt(token)); } catch (Exception e) { return null; }
         } else return null;
      }
      return current;
   }
   static JsonElement bounded(JsonElement value, int maxChars) {
      String json = GSON.toJson(value);
      if (json.length() <= maxChars) return value.deepCopy();
      JsonObject result = new JsonObject(); result.addProperty("truncated", true); result.addProperty("original_chars", json.length()); result.addProperty("preview", json.substring(0, Math.min(maxChars / 2, json.length()))); return result;
   }
   static String safe(Throwable error) {
      return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
   }
   static String now() { return Instant.now().toString(); }
}
