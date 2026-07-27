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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SimulationSafety {
   private static final Pattern FIRST_KEYWORD = Pattern.compile("^\\s*([a-z]+)", 2);
   private static final Set<String> ALLOWED = Set.of("insert", "update", "delete", "merge");
   private static final Pattern FORBIDDEN = Pattern.compile(
      "\\b(create|alter|drop|truncate|grant|revoke|call|exec|execute|copy|load|unload|vacuum|attach|detach|pragma|begin|commit|rollback|savepoint|release)\\b",
      2
   );

   private SimulationSafety() {
   }

   static String validate(String sql) {
      String stripped = SqlSafety.stripStringsAndComments(sql).trim();
      Matcher matcher = FIRST_KEYWORD.matcher(stripped);
      if (!matcher.find()) {
         throw new IllegalArgumentException("Simulation SQL is empty or unrecognized");
      } else {
         String operation = matcher.group(1).toLowerCase(Locale.ENGLISH);
         if (!ALLOWED.contains(operation)) {
            throw new IllegalArgumentException("Simulation only supports INSERT, UPDATE, DELETE, or MERGE");
         } else if (FORBIDDEN.matcher(stripped).find()) {
            throw new IllegalArgumentException("Simulation SQL contains DDL, procedure execution, session settings, or transaction control");
         } else {
            String withoutTrailing = stripped.replaceFirst(";\\s*$", "");
            if (withoutTrailing.indexOf(59) >= 0) {
               throw new IllegalArgumentException("Simulation accepts exactly one SQL statement");
            } else {
               return operation;
            }
         }
      }
   }
}
