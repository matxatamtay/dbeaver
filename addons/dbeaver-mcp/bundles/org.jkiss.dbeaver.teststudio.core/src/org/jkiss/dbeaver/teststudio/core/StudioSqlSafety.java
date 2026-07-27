package org.jkiss.dbeaver.teststudio.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class StudioSqlSafety {
   private static final Set<String> READ_PREFIXES = Set.of(
      "select", "with", "show", "describe", "desc", "explain", "pragma", "values"
   );
   private static final Pattern MUTATION = Pattern.compile(
      "\\b(insert|update|delete|merge|replace|create|alter|drop|truncate|grant|revoke|call|exec|execute|do|copy|vacuum|analyze|reindex|attach|detach|begin|commit|rollback|savepoint|release|set|reset)\\b",
      Pattern.CASE_INSENSITIVE
   );

   private StudioSqlSafety() {
   }

   static boolean isReadOnly(String sql) {
      String normalized = strip(sql).trim().toLowerCase(Locale.ENGLISH);
      if (normalized.isEmpty()) return false;
      String prefix = normalized.split("\\s+", 2)[0];
      return READ_PREFIXES.contains(prefix) && !MUTATION.matcher(normalized).find();
   }

   private static String strip(String sql) {
      StringBuilder result = new StringBuilder(sql.length());
      boolean single = false, doubleQuoted = false, line = false, block = false;
      for (int index = 0; index < sql.length(); index++) {
         char ch = sql.charAt(index);
         char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
         if (line) {
            if (ch == '\n') { line = false; result.append(' '); }
            continue;
         }
         if (block) {
            if (ch == '*' && next == '/') { block = false; index++; result.append(' '); }
            continue;
         }
         if (!single && !doubleQuoted && ch == '-' && next == '-') { line = true; index++; continue; }
         if (!single && !doubleQuoted && ch == '/' && next == '*') { block = true; index++; continue; }
         if (!doubleQuoted && ch == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) { single = !single; result.append(' '); continue; }
         if (!single && ch == '"' && (index == 0 || sql.charAt(index - 1) != '\\')) { doubleQuoted = !doubleQuoted; result.append(' '); continue; }
         result.append(single || doubleQuoted ? ' ' : ch);
      }
      return result.toString();
   }
}
