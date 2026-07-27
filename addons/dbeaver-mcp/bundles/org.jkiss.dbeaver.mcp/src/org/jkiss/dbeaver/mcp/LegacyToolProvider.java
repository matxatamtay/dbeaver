/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

final class LegacyToolProvider implements DBeaverMcpToolProvider {
   private final McpToolRegistry registry;

   LegacyToolProvider(McpToolRegistry registry) {
      this.registry = registry;
   }

   @Override
   public String id() {
      return "legacy";
   }

   @Override
   public int priority() {
      return 0;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      DBeaverTools.registerLegacyTools(this.registry, context);
   }
}
