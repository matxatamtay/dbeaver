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

import java.util.Locale;
import java.util.regex.Pattern;

final class SqlSafety {
   private static final Pattern WRITE_KEYWORD = Pattern.compile(
      "\\b(insert|update|delete|merge|replace|upsert|create|alter|drop|truncate|grant|revoke|call|exec|execute|copy|load|unload|vacuum|analyze|attach|detach|set|into|comment|rename|refresh|reindex|cluster|checkpoint|lock|begin|commit|rollback|savepoint|release|pragma)\\b",
      2
   );

   private SqlSafety() {
   }

   static boolean isReadOnly(String sql) {
      String normalized = stripStringsAndComments(sql).trim().toLowerCase(Locale.ENGLISH);
      return !normalized.isEmpty() && !WRITE_KEYWORD.matcher(normalized).find()
         ? normalized.startsWith("select")
            || normalized.startsWith("with")
            || normalized.startsWith("show")
            || normalized.startsWith("describe")
            || normalized.startsWith("desc")
            || normalized.startsWith("explain")
            || normalized.startsWith("values")
         : false;
   }

   static String stripStringsAndComments(String sql) {
      StringBuilder result = new StringBuilder(sql.length());
      boolean singleQuote = false;
      boolean doubleQuote = false;
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
         } else if (!singleQuote && !doubleQuote && current == '-' && next == '-') {
            lineComment = true;
            result.append("  ");
            index++;
         } else if (!singleQuote && !doubleQuote && current == '/' && next == '*') {
            blockComment = true;
            result.append("  ");
            index++;
         } else if (!doubleQuote && current == '\'') {
            if (singleQuote && next == '\'') {
               result.append("  ");
               index++;
            } else {
               singleQuote = !singleQuote;
               result.append(' ');
            }
         } else if (!singleQuote && current == '"') {
            if (doubleQuote && next == '"') {
               result.append("  ");
               index++;
            } else {
               doubleQuote = !doubleQuote;
               result.append(' ');
            }
         } else {
            result.append(!singleQuote && !doubleQuote ? current : ' ');
         }
      }

      return result.toString();
   }
}
