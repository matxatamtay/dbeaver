package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonObject;

public final class McpAuditTest {
   public static void main(String[] args) {
      DBeaverMcpAudit audit = new DBeaverMcpAudit();
      audit.record("tool_a", true, 2_000_000L, "");
      audit.record("tool_a", false, 4_000_000L, "IllegalArgumentException");
      audit.record("tool_b", true, 1_000_000L, "");

      JsonObject list = audit.list(10);
      check(list.get("count").getAsInt() == 3, "audit should retain metadata entries");
      check(list.toString().contains("metadata_only_no_arguments_sql_results_or_credentials"), "privacy statement should be explicit");

      JsonObject metrics = audit.metrics();
      check(metrics.get("tool_count").getAsInt() == 2, "metrics should aggregate by tool");
      JsonObject first = metrics.getAsJsonArray("tools").get(0).getAsJsonObject();
      check("tool_a".equals(first.get("tool").getAsString()), "metrics should be sorted by tool");
      check(first.get("calls").getAsLong() == 2L, "call count should be exact");
      check(first.get("success").getAsLong() == 1L, "success count should be exact");
      check(first.get("failures").getAsLong() == 1L, "failure count should be exact");
      check(Math.abs(first.get("avg_latency_ms").getAsDouble() - 3.0) < 0.0001, "average latency should be correct");

      check(audit.clear().get("removed_entries").getAsInt() == 3, "clear should report removed entries");
      check(audit.list(10).get("count").getAsInt() == 0, "clear should remove entries");
      System.out.println("McpAuditTest passed");
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
