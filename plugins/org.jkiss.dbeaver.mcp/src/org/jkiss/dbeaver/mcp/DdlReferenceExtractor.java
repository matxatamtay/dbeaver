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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DdlReferenceExtractor {
   private static final String IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$#]*|\\\"(?:\\\"\\\"|[^\\\"])+\\\"|`[^`]+`|\\[[^]]+])";
   private static final Pattern REFERENCE = Pattern.compile(
      "(?is)\\b(from|join|update|insert\\s+into|delete\\s+from|merge\\s+into|call(?:\\s+procedure)?|execute(?:\\s+(?:function|procedure))?|exec)\\s+((?:[A-Za-z_][A-Za-z0-9_$#]*|\\\"(?:\\\"\\\"|[^\\\"])+\\\"|`[^`]+`|\\[[^]]+])(?:\\s*\\.\\s*(?:[A-Za-z_][A-Za-z0-9_$#]*|\\\"(?:\\\"\\\"|[^\\\"])+\\\"|`[^`]+`|\\[[^]]+])){0,3})"
   );
   private static final Pattern DYNAMIC_SQL = Pattern.compile(
      "(?is)\\b(execute\\s+immediate|sp_executesql|prepare\\s+[^;]+\\s+from|eval\\s*\\(|return\\s+query\\s+execute\\b|execute\\s+format\\s*\\(|execute\\s+[^;\\n]+\\|\\|)"
   );

   private DdlReferenceExtractor() {
   }

   static List<DdlReferenceExtractor.Reference> extract(String ddl) {
      String searchable = stripSingleQuotedStringsAndComments(ddl);
      Matcher matcher = REFERENCE.matcher(searchable);
      Set<String> seen = new LinkedHashSet<>();
      List<DdlReferenceExtractor.Reference> result = new ArrayList<>();

      while (matcher.find()) {
         String operation = matcher.group(1).replaceAll("\\s+", "_").toLowerCase(Locale.ENGLISH);
         if (operation.startsWith("execute_")) {
            operation = "execute";
         } else if (operation.startsWith("call_")) {
            operation = "call";
         }

         String objectName = matcher.group(2).replaceAll("\\s*\\.\\s*", ".").trim();
         String normalizedObject = objectName.replace("\"", "").replace("`", "").toLowerCase(Locale.ENGLISH);
         if (!operation.equals("update") || !Set.of("of", "on", "or").contains(normalizedObject)) {
            String key = operation + "\u0000" + objectName.toLowerCase(Locale.ENGLISH);
            if (seen.add(key)) {
               result.add(new DdlReferenceExtractor.Reference(operation, objectName));
            }
         }
      }

      return List.copyOf(result);
   }

   static boolean hasDynamicSql(String ddl) {
      return DYNAMIC_SQL.matcher(stripSingleQuotedStringsAndComments(ddl)).find();
   }

   private static String stripSingleQuotedStringsAndComments(String sql) {
      StringBuilder result = new StringBuilder(sql.length());
      boolean singleQuote = false;
      boolean lineComment = false;
      boolean blockComment = false;

      for (int index = 0; index < sql.length(); index++) {
         char current = sql.charAt(index);
         char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
         if (lineComment) {
            if (current != '\n' && current != '\r') {
               result.append(' ');
            } else {
               lineComment = false;
               result.append(current);
            }
         } else if (blockComment) {
            if (current == '*' && next == '/') {
               blockComment = false;
               result.append("  ");
               index++;
            } else {
               result.append(current != '\n' && current != '\r' ? ' ' : current);
            }
         } else if (!singleQuote && current == '-' && next == '-') {
            lineComment = true;
            result.append("  ");
            index++;
         } else if (!singleQuote && current == '/' && next == '*') {
            blockComment = true;
            result.append("  ");
            index++;
         } else if (current == '\'') {
            if (singleQuote && next == '\'') {
               result.append("  ");
               index++;
            } else {
               singleQuote = !singleQuote;
               result.append(' ');
            }
         } else {
            result.append(singleQuote ? ' ' : current);
         }
      }

      return result.toString();
   }

   record Reference(String operation, String objectName) {
   }
}
