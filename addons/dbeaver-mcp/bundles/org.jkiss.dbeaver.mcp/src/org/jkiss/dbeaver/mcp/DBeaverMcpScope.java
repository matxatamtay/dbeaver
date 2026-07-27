/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum DBeaverMcpScope {
   OBSERVE,
   QUERY,
   DATA_WRITE,
   SCHEMA_WRITE,
   TRANSFER,
   TASK,
   TEST,
   ADMIN,
   WORKSPACE,
   UI;

   public String id() {
      return this.name().toLowerCase(Locale.ENGLISH);
   }

   static Set<DBeaverMcpScope> inferLegacy(String name, boolean readOnly, boolean destructive) {
      if (name.contains("editor") || name.contains("selection") || name.contains("propose_sql") || name.contains("save_sql") || name.contains("select_connection")) {
         return EnumSet.of(UI);
      }
      if (name.contains("simulate_change") || name.endsWith("_commit") || name.endsWith("_rollback") || name.contains("begin_transaction")) {
         return EnumSet.of(DATA_WRITE);
      }
      if (name.contains("sql") || name.contains("query") || name.contains("result") || name.contains("transaction") || name.contains("sample_rows") || name.contains("profile_table")) {
         return EnumSet.of(QUERY);
      }
      return readOnly && !destructive ? EnumSet.of(OBSERVE) : EnumSet.of(QUERY);
   }

   static JsonArray toJson(Set<DBeaverMcpScope> scopes) {
      JsonArray result = new JsonArray();
      scopes.stream().map(DBeaverMcpScope::id).sorted().forEach(result::add);
      return result;
   }
}
