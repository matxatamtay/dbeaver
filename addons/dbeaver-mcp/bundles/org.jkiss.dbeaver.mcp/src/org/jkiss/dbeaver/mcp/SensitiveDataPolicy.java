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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

final class SensitiveDataPolicy {
   private static final Map<String, Pattern> NAME_PATTERNS = new LinkedHashMap<>();

   private SensitiveDataPolicy() {
   }

   static String classify(String columnName, String typeName) {
      for (Entry<String, Pattern> entry : NAME_PATTERNS.entrySet()) {
         if (entry.getValue().matcher(columnName).find()) {
            return entry.getKey();
         }
      }

      String normalizedType = typeName.toLowerCase(Locale.ENGLISH);
      return !normalizedType.contains("blob") && !normalizedType.contains("binary") ? null : "binary_data";
   }

   static boolean alwaysMask(String category) {
      return category.equals("password") || category.equals("token") || category.equals("card_number") || category.equals("national_id");
   }

   static JsonObject maskQueryPayload(JsonObject original, boolean maskSensitive) {
      JsonObject payload = original.deepCopy();
      JsonElement columnsElement = payload.get("columns");
      JsonElement rowsElement = payload.get("rows");
      if (columnsElement != null && columnsElement.isJsonArray() && rowsElement != null && rowsElement.isJsonArray()) {
         Map<String, String> categories = new LinkedHashMap<>();

         for (JsonElement element : columnsElement.getAsJsonArray()) {
            if (element.isJsonObject()) {
               JsonObject column = element.getAsJsonObject();
               String name = McpJson.getString(column, "label", McpJson.getString(column, "name", ""));
               String type = McpJson.getString(column, "type", "");
               String category = classify(name, type);
               if (category != null) {
                  categories.put(name, category);
                  column.addProperty("sensitive_category", category);
                  column.addProperty("masked", maskSensitive || alwaysMask(category));
               }
            }
         }

         for (JsonElement rowElement : rowsElement.getAsJsonArray()) {
            if (rowElement.isJsonObject()) {
               JsonObject row = rowElement.getAsJsonObject();

               for (Entry<String, String> entry : categories.entrySet()) {
                  if ((maskSensitive || alwaysMask(entry.getValue())) && row.has(entry.getKey()) && !row.get(entry.getKey()).isJsonNull()) {
                     row.addProperty(entry.getKey(), "<masked:" + entry.getValue() + ">");
                  }
               }
            }
         }

         payload.addProperty("sensitive_values_masked", maskSensitive || categories.values().stream().anyMatch(SensitiveDataPolicy::alwaysMask));
         return payload;
      } else {
         return payload;
      }
   }

   static {
      NAME_PATTERNS.put("password", Pattern.compile("(?i)(password|passwd|pwd|pass_hash|password_hash)"));
      NAME_PATTERNS.put("token", Pattern.compile("(?i)(token|secret|api[_-]?key|access[_-]?key|private[_-]?key|credential)"));
      NAME_PATTERNS.put("card_number", Pattern.compile("(?i)(card|pan|credit).*?(number|no|num)|(^|_)cvv($|_)|(^|_)cvc($|_)"));
      NAME_PATTERNS.put("national_id", Pattern.compile("(?i)(national|social|tax|passport|identity).*?(id|number|no)|(^|_)ssn($|_)"));
      NAME_PATTERNS.put("email", Pattern.compile("(?i)(^|_)(email|e_mail|mail_address)($|_)"));
      NAME_PATTERNS.put("phone", Pattern.compile("(?i)(^|_)(phone|mobile|telephone|tel)($|_)"));
      NAME_PATTERNS.put("address", Pattern.compile("(?i)(address|street|postal|postcode|zip_code|latitude|longitude|geo)"));
      NAME_PATTERNS.put("date_of_birth", Pattern.compile("(?i)(date_of_birth|birth_date|dob)"));
      NAME_PATTERNS.put("medical", Pattern.compile("(?i)(diagnosis|medical|health|patient|prescription)"));
      NAME_PATTERNS.put("financial", Pattern.compile("(?i)(bank|iban|swift|routing|account_number|salary|income)"));
   }
}
