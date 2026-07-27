/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;

public record DBeaverMcpToolDefinition(
   String name,
   String description,
   JsonObject inputSchema,
   Set<DBeaverMcpScope> scopes,
   boolean readOnly,
   boolean destructive,
   boolean idempotent,
   Handler handler
) {
   public DBeaverMcpToolDefinition {
      if (name == null || name.isBlank()) {
         throw new IllegalArgumentException("Tool name is required");
      }
      if (description == null || description.isBlank()) {
         throw new IllegalArgumentException("Tool description is required");
      }
      inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
      scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
      handler = Objects.requireNonNull(handler, "handler");
   }

   @FunctionalInterface
   public interface Handler {
      JsonObject execute(JsonObject arguments) throws Exception;
   }
}
