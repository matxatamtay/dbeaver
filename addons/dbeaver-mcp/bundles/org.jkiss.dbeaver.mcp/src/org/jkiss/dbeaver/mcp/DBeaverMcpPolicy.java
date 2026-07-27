/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonObject;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class DBeaverMcpPolicy {
   public static final String PROPERTY_SCOPES = "dbeaver.mcp.scopes";
   public static final String ENV_SCOPES = "DBEAVER_MCP_SCOPES";

   private final Set<DBeaverMcpScope> allowedScopes;

   private DBeaverMcpPolicy(Set<DBeaverMcpScope> allowedScopes) {
      this.allowedScopes = Set.copyOf(allowedScopes);
   }

   public static DBeaverMcpPolicy allowAll() {
      return new DBeaverMcpPolicy(EnumSet.allOf(DBeaverMcpScope.class));
   }

   public static DBeaverMcpPolicy fromEnvironment() {
      String configured = System.getProperty(PROPERTY_SCOPES);
      if (configured == null) {
         configured = System.getenv(ENV_SCOPES);
      }
      return configured == null || configured.isBlank() ? allowAll() : parse(configured);
   }

   public static DBeaverMcpPolicy parse(String value) {
      EnumSet<DBeaverMcpScope> scopes = EnumSet.noneOf(DBeaverMcpScope.class);
      for (String item : value.split(",")) {
         String normalized = item.trim().toUpperCase(Locale.ENGLISH).replace('-', '_');
         if (normalized.isEmpty() || "NONE".equals(normalized)) {
            continue;
         }
         if ("ALL".equals(normalized)) {
            return allowAll();
         }
         try {
            scopes.add(DBeaverMcpScope.valueOf(normalized));
         } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown DBeaver MCP scope: " + item.trim());
         }
      }
      return new DBeaverMcpPolicy(scopes);
   }

   public boolean allows(Set<DBeaverMcpScope> requiredScopes) {
      return this.allowedScopes.containsAll(requiredScopes);
   }

   public Set<DBeaverMcpScope> allowedScopes() {
      return this.allowedScopes;
   }

   public JsonObject describe() {
      JsonObject result = new JsonObject();
      result.add("allowed_scopes", DBeaverMcpScope.toJson(this.allowedScopes));
      result.addProperty("source", System.getProperty(PROPERTY_SCOPES) != null ? "system_property" : System.getenv(ENV_SCOPES) != null ? "environment" : "default_all");
      return result;
   }
}
