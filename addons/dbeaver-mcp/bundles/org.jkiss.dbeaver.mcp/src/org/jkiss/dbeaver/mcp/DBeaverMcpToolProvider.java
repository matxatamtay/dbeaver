/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

public interface DBeaverMcpToolProvider {
   String id();

   default int priority() {
      return 100;
   }

   void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) throws Exception;
}
