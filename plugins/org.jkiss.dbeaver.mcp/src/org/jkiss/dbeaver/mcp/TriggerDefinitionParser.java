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
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TriggerDefinitionParser {
   private static final Pattern TIMING = Pattern.compile("(?i)\\b(before|after|instead\\s+of)\\b");
   private static final Pattern EVENT = Pattern.compile("(?i)\\b(insert|update|delete|truncate)\\b");
   private static final Pattern UPDATE_OF = Pattern.compile("(?is)\\bupdate\\s+of\\s+(.+?)\\s+(?:on|for\\s+each|when|begin|execute)\\b");
   private static final Pattern LEVEL = Pattern.compile("(?i)\\bfor\\s+each\\s+(row|statement)\\b");
   private static final Pattern ENABLED = Pattern.compile("(?i)\\b(disable|disabled|enable|enabled)\\b");
   private static final Pattern WHEN = Pattern.compile("(?is)\\bwhen\\s*\\((.*?)\\)\\s*(?:begin|execute|for\\s+each)");

   private TriggerDefinitionParser() {
   }

   static JsonObject parse(String ddl) {
      String searchable = SqlSafety.stripStringsAndComments(ddl);
      JsonObject result = new JsonObject();
      Matcher timing = TIMING.matcher(searchable);
      if (timing.find()) {
         result.addProperty("timing", timing.group(1).replaceAll("\\s+", "_").toLowerCase(Locale.ENGLISH));
      }

      Set<String> eventNames = new LinkedHashSet<>();
      int headerEnd = searchable.toLowerCase(Locale.ENGLISH).indexOf(" on ");
      String eventSource = headerEnd > 0 ? searchable.substring(0, headerEnd) : searchable;
      Matcher events = EVENT.matcher(eventSource);

      while (events.find()) {
         eventNames.add(events.group(1).toLowerCase(Locale.ENGLISH));
      }

      JsonArray eventArray = new JsonArray();
      eventNames.forEach(eventArray::add);
      result.add("events", eventArray);
      Matcher columns = UPDATE_OF.matcher(searchable);
      JsonArray columnArray = new JsonArray();
      if (columns.find()) {
         for (String column : columns.group(1).split(",")) {
            String clean = column.trim().replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
            if (!clean.isBlank()) {
               columnArray.add(clean);
            }
         }
      }

      result.add("update_columns", columnArray);
      Matcher level = LEVEL.matcher(searchable);
      if (level.find()) {
         result.addProperty("level", level.group(1).toLowerCase(Locale.ENGLISH));
      }

      Matcher enabled = ENABLED.matcher(searchable);
      if (enabled.find()) {
         result.addProperty("enabled_state", enabled.group(1).toLowerCase(Locale.ENGLISH));
      }

      Matcher condition = WHEN.matcher(searchable);
      if (condition.find()) {
         result.addProperty("condition", condition.group(1).trim());
      }

      result.addProperty("confidence", "heuristic_ddl_parse");
      return result;
   }
}
