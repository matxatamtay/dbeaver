/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonObject;

public record DBeaverMcpContext(
   int port,
   boolean authRequired,
   DBeaverMcpJobManager jobs,
   DBeaverMcpPolicy policy,
   DBeaverMcpToolInvoker tools
) {
   private static final DBeaverMcpToolInvoker UNAVAILABLE = new DBeaverMcpToolInvoker() {
      @Override
      public JsonObject invoke(String name, JsonObject arguments) {
         throw new IllegalStateException("MCP tool invoker is unavailable in this context");
      }

      @Override
      public JsonObject describe(String name) {
         throw new IllegalStateException("MCP tool invoker is unavailable in this context");
      }

      @Override
      public JsonObject list() {
         return new JsonObject();
      }
   };

   /** Source-compatible constructor for providers compiled against the Phase 0 context. */
   public DBeaverMcpContext(int port, boolean authRequired, DBeaverMcpJobManager jobs, DBeaverMcpPolicy policy) {
      this(port, authRequired, jobs, policy, UNAVAILABLE);
   }
}
