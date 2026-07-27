package org.jkiss.dbeaver.mcp;

/** Allows additive provider bundles to request the standard MCP policy-denied error. */
public final class DBeaverMcpAccessDeniedException extends Exception {
   public DBeaverMcpAccessDeniedException(String message) {
      super(message);
   }
}
