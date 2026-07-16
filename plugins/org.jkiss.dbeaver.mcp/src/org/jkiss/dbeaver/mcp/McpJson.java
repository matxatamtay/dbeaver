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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

final class McpJson {
   static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
   static final int MAX_VALUE_CHARS = 65536;

   private McpJson() {
   }

   static JsonObject getObject(JsonObject object, String name) {
      JsonElement value = object.get(name);
      return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
   }

   static String requiredString(JsonObject object, String name) {
      String value = getString(object, name, "").trim();
      if (value.isEmpty()) {
         throw new IllegalArgumentException("Missing required string: " + name);
      } else {
         return value;
      }
   }

   static String getString(JsonObject object, String name, String defaultValue) {
      JsonElement value = object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsString() : defaultValue;
   }

   static boolean getBoolean(JsonObject object, String name, boolean defaultValue) {
      JsonElement value = object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : defaultValue;
   }

   static int getInt(JsonObject object, String name, int defaultValue, int minimum, int maximum) {
      JsonElement value = object.get(name);
      if (value != null && value.isJsonPrimitive()) {
         int parsed = value.getAsInt();
         if (parsed >= minimum && parsed <= maximum) {
            return parsed;
         } else {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
         }
      } else {
         return defaultValue;
      }
   }

   static List<String> getStrings(JsonObject object, String name) {
      JsonElement value = object.get(name);
      if (value != null && value.isJsonArray()) {
         ArrayList<String> result = new ArrayList<>();

         for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
               result.add(item.getAsString());
            }
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   static JsonObject objectSchema(Map<String, JsonObject> properties) {
      return objectSchema(properties, List.of());
   }

   static JsonObject objectSchema(Map<String, JsonObject> properties, Collection<String> required) {
      JsonObject schema = new JsonObject();
      schema.addProperty("type", "object");
      JsonObject propertyObject = new JsonObject();
      properties.forEach(propertyObject::add);
      schema.add("properties", propertyObject);
      schema.addProperty("additionalProperties", false);
      if (!required.isEmpty()) {
         JsonArray requiredArray = new JsonArray();
         required.forEach(requiredArray::add);
         schema.add("required", requiredArray);
      }

      return schema;
   }

   static JsonObject stringProperty(String description) {
      JsonObject property = new JsonObject();
      property.addProperty("type", "string");
      property.addProperty("description", description);
      return property;
   }

   static JsonObject booleanProperty(String description) {
      JsonObject property = new JsonObject();
      property.addProperty("type", "boolean");
      property.addProperty("description", description);
      return property;
   }

   static JsonObject integerProperty(String description, int minimum, int maximum) {
      JsonObject property = new JsonObject();
      property.addProperty("type", "integer");
      property.addProperty("description", description);
      property.addProperty("minimum", minimum);
      property.addProperty("maximum", maximum);
      return property;
   }

   static JsonObject objectProperty(String description) {
      JsonObject property = new JsonObject();
      property.addProperty("type", "object");
      property.addProperty("description", description);
      property.addProperty("additionalProperties", true);
      return property;
   }

   static JsonObject stringArrayProperty(String description) {
      JsonObject property = new JsonObject();
      property.addProperty("type", "array");
      property.addProperty("description", description);
      JsonObject items = new JsonObject();
      items.addProperty("type", "string");
      property.add("items", items);
      return property;
   }

   static JsonObject toolResult(JsonObject payload, boolean isError) {
      JsonObject result = new JsonObject();
      JsonArray content = new JsonArray();
      JsonObject text = new JsonObject();
      text.addProperty("type", "text");
      text.addProperty("text", GSON.toJson(payload));
      content.add(text);
      result.add("content", content);
      result.add("structuredContent", payload);
      result.addProperty("isError", isError);
      return result;
   }

   static JsonElement toJsonValue(Object value) {
      if (value == null) {
         return JsonNull.INSTANCE;
      } else if (value instanceof Number number) {
         return GSON.toJsonTree(number);
      } else if (value instanceof Boolean bool) {
         return GSON.toJsonTree(bool);
      } else if (value instanceof byte[] bytes) {
         return GSON.toJsonTree(Base64.getEncoder().encodeToString(bytes));
      } else if (value instanceof char[] chars) {
         return GSON.toJsonTree(truncate(new String(chars)));
      } else {
         return !(value instanceof CharSequence) && !(value instanceof Date) && !(value instanceof TemporalAccessor) && !(value instanceof Enum)
            ? GSON.toJsonTree(truncate(String.valueOf(value)))
            : GSON.toJsonTree(truncate(String.valueOf(value)));
      }
   }

   static String uniqueColumnKey(JsonObject row, String label, int index) {
      String base = label.isBlank() ? "column_" + (index + 1) : label;
      if (!row.has(base)) {
         return base;
      } else {
         int suffix = 2;

         while (row.has(base + "_" + suffix)) {
            suffix++;
         }

         return base + "_" + suffix;
      }
   }

   static String truncate(String value) {
      return value.length() <= 65536 ? value : value.substring(0, 65536) + "\u2026[truncated]";
   }

   static String safeMessage(Throwable throwable) {
      String message = throwable.getMessage();
      return message != null && !message.isBlank() ? message : throwable.getClass().getSimpleName();
   }
}
