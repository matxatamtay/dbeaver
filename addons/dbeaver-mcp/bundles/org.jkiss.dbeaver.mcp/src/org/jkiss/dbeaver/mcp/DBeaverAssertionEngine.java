/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class DBeaverAssertionEngine {
   private static final int MAX_ASSERTIONS = 100;

   private DBeaverAssertionEngine() {
   }

   static JsonObject evaluate(JsonElement value, JsonArray assertions) {
      if (assertions.size() > MAX_ASSERTIONS) {
         throw new IllegalArgumentException("A test case may contain at most " + MAX_ASSERTIONS + " assertions");
      }
      JsonArray results = new JsonArray();
      int passed = 0;
      for (int index = 0; index < assertions.size(); index++) {
         JsonElement element = assertions.get(index);
         if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Assertion " + (index + 1) + " must be an object");
         }
         JsonObject assertion = element.getAsJsonObject();
         JsonObject result = evaluateOne(value, assertion, index);
         if (result.get("passed").getAsBoolean()) {
            passed++;
         }
         results.add(result);
      }
      JsonObject summary = new JsonObject();
      summary.addProperty("passed", passed == assertions.size());
      summary.addProperty("assertion_count", assertions.size());
      summary.addProperty("passed_count", passed);
      summary.addProperty("failed_count", assertions.size() - passed);
      summary.add("assertions", results);
      return summary;
   }

   static void validate(JsonArray assertions) {
      evaluate(JsonNull.INSTANCE, assertions);
   }

   private static JsonObject evaluateOne(JsonElement root, JsonObject assertion, int index) {
      String path = McpJson.getString(assertion, "path", "");
      String operator = McpJson.requiredString(assertion, "operator").toLowerCase();
      PathValue selected = select(root, path);
      JsonElement expected = assertion.has("expected") ? assertion.get("expected") : JsonNull.INSTANCE;
      boolean passed;
      String error = "";
      try {
         passed = switch (operator) {
            case "exists" -> selected.exists();
            case "absent", "not_exists" -> !selected.exists();
            case "equals", "eq" -> selected.exists() && selected.value().equals(expected);
            case "not_equals", "ne" -> !selected.exists() || !selected.value().equals(expected);
            case "contains" -> selected.exists() && contains(selected.value(), expected);
            case "gt" -> compareNumbers(selected, expected) > 0;
            case "gte" -> compareNumbers(selected, expected) >= 0;
            case "lt" -> compareNumbers(selected, expected) < 0;
            case "lte" -> compareNumbers(selected, expected) <= 0;
            case "size_equals", "size" -> selected.exists() && size(selected.value()) == requireInt(expected, "expected");
            case "empty" -> selected.exists() && size(selected.value()) == 0;
            case "not_empty" -> selected.exists() && size(selected.value()) > 0;
            case "is_true" -> selected.exists() && selected.value().isJsonPrimitive() && selected.value().getAsBoolean();
            case "is_false" -> selected.exists() && selected.value().isJsonPrimitive() && !selected.value().getAsBoolean();
            case "is_null" -> selected.exists() && selected.value().isJsonNull();
            case "not_null" -> selected.exists() && !selected.value().isJsonNull();
            case "type" -> selected.exists() && typeOf(selected.value()).equals(McpJson.getString(assertion, "expected", ""));
            default -> throw new IllegalArgumentException("Unsupported assertion operator: " + operator);
         };
      } catch (RuntimeException e) {
         passed = false;
         error = McpJson.safeMessage(e);
      }
      JsonObject result = new JsonObject();
      result.addProperty("index", index);
      result.addProperty("path", path);
      result.addProperty("operator", operator);
      result.addProperty("passed", passed);
      result.addProperty("exists", selected.exists());
      if (assertion.has("expected")) {
         result.add("expected", bounded(assertion.get("expected")));
      }
      if (selected.exists()) {
         result.add("actual", bounded(selected.value()));
      }
      if (!error.isBlank()) {
         result.addProperty("error", error);
      }
      String message = McpJson.getString(assertion, "message", "");
      if (!message.isBlank()) {
         result.addProperty("message", McpJson.truncate(message));
      }
      return result;
   }

   private static PathValue select(JsonElement root, String pointer) {
      if (pointer == null || pointer.isEmpty()) {
         return new PathValue(true, root == null ? JsonNull.INSTANCE : root);
      }
      if (!pointer.startsWith("/")) {
         throw new IllegalArgumentException("Assertion path must be an RFC 6901 JSON Pointer starting with /");
      }
      JsonElement current = root == null ? JsonNull.INSTANCE : root;
      for (String raw : pointer.substring(1).split("/", -1)) {
         String token = raw.replace("~1", "/").replace("~0", "~");
         if (current.isJsonObject()) {
            JsonObject object = current.getAsJsonObject();
            if (!object.has(token)) {
               return new PathValue(false, JsonNull.INSTANCE);
            }
            current = object.get(token);
         } else if (current.isJsonArray()) {
            int index;
            try {
               index = Integer.parseInt(token);
            } catch (NumberFormatException e) {
               return new PathValue(false, JsonNull.INSTANCE);
            }
            JsonArray array = current.getAsJsonArray();
            if (index < 0 || index >= array.size()) {
               return new PathValue(false, JsonNull.INSTANCE);
            }
            current = array.get(index);
         } else {
            return new PathValue(false, JsonNull.INSTANCE);
         }
      }
      return new PathValue(true, current == null ? JsonNull.INSTANCE : current);
   }

   private static boolean contains(JsonElement actual, JsonElement expected) {
      if (actual.isJsonArray()) {
         for (JsonElement item : actual.getAsJsonArray()) {
            if (item.equals(expected)) {
               return true;
            }
         }
         return false;
      }
      if (actual.isJsonObject()) {
         return expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isString()
            && actual.getAsJsonObject().has(expected.getAsString());
      }
      return actual.isJsonPrimitive() && expected.isJsonPrimitive()
         && actual.getAsString().contains(expected.getAsString());
   }

   private static int compareNumbers(PathValue selected, JsonElement expected) {
      if (!selected.exists() || !selected.value().isJsonPrimitive() || !selected.value().getAsJsonPrimitive().isNumber()) {
         throw new IllegalArgumentException("Actual value is not numeric");
      }
      if (!expected.isJsonPrimitive() || !expected.getAsJsonPrimitive().isNumber()) {
         throw new IllegalArgumentException("Expected value is not numeric");
      }
      return new BigDecimal(selected.value().getAsString()).compareTo(new BigDecimal(expected.getAsString()));
   }

   private static int requireInt(JsonElement value, String name) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
         throw new IllegalArgumentException(name + " must be an integer");
      }
      return value.getAsInt();
   }

   private static int size(JsonElement value) {
      if (value.isJsonArray()) {
         return value.getAsJsonArray().size();
      }
      if (value.isJsonObject()) {
         return value.getAsJsonObject().size();
      }
      if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
         return value.getAsString().length();
      }
      throw new IllegalArgumentException("Value does not have a size");
   }

   private static String typeOf(JsonElement value) {
      if (value == null || value.isJsonNull()) return "null";
      if (value.isJsonArray()) return "array";
      if (value.isJsonObject()) return "object";
      if (value.getAsJsonPrimitive().isBoolean()) return "boolean";
      if (value.getAsJsonPrimitive().isNumber()) return "number";
      return "string";
   }

   private static JsonElement bounded(JsonElement value) {
      String json = McpJson.GSON.toJson(value);
      if (json.length() <= 4096) {
         return value.deepCopy();
      }
      JsonObject truncated = new JsonObject();
      truncated.addProperty("truncated", true);
      truncated.addProperty("preview", json.substring(0, 4096));
      return truncated;
   }

   static JsonArray array(JsonObject arguments, String name) {
      JsonElement value = arguments.get(name);
      if (value == null || value.isJsonNull()) {
         return new JsonArray();
      }
      if (!value.isJsonArray()) {
         throw new IllegalArgumentException(name + " must be an array");
      }
      return value.getAsJsonArray();
   }

   static List<JsonObject> objectList(JsonObject arguments, String name, int maximum) {
      JsonArray array = array(arguments, name);
      if (array.size() > maximum) {
         throw new IllegalArgumentException(name + " may contain at most " + maximum + " items");
      }
      List<JsonObject> result = new ArrayList<>();
      for (JsonElement element : array) {
         if (!element.isJsonObject()) {
            throw new IllegalArgumentException(name + " items must be objects");
         }
         result.add(element.getAsJsonObject());
      }
      return List.copyOf(result);
   }

   private record PathValue(boolean exists, JsonElement value) {
   }
}
