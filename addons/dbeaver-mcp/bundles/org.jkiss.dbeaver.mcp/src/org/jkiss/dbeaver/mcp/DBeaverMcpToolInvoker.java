/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonObject;

/**
 * Policy-preserving access to MCP tools for additive provider bundles.
 * Implementations must invoke the same registry path used by external MCP clients.
 */
public interface DBeaverMcpToolInvoker {
   JsonObject invoke(String name, JsonObject arguments) throws Exception;

   JsonObject describe(String name) throws Exception;

   JsonObject list();
}
