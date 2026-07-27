package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class TesterPlatformTest {
   public static void main(String[] args) throws Exception {
      testAssertions();
      testSnapshotDiff();
      System.out.println("TesterPlatformTest passed");
   }

   private static void testAssertions() {
      JsonObject value = new JsonObject();
      value.addProperty("status", "ok");
      JsonArray rows = new JsonArray();
      JsonObject row = new JsonObject();
      row.addProperty("count", 3);
      rows.add(row);
      value.add("rows", rows);

      JsonArray assertions = new JsonArray();
      assertions.add(assertion("/status", "equals", "ok"));
      assertions.add(assertion("/rows", "size_equals", 1));
      assertions.add(assertion("/rows/0/count", "gte", 3));
      JsonObject result = DBeaverAssertionEngine.evaluate(value, assertions);
      check(result.get("passed").getAsBoolean(), "all assertions should pass");

      JsonArray failing = new JsonArray();
      failing.add(assertion("/rows/0/count", "lt", 2));
      JsonObject failed = DBeaverAssertionEngine.evaluate(value, failing);
      check(!failed.get("passed").getAsBoolean(), "numeric assertion should fail");
      check(failed.get("failed_count").getAsInt() == 1, "failure count should be reported");
   }

   private static void testSnapshotDiff() throws Exception {
      DBeaverTestStore store = new DBeaverTestStore();
      JsonObject left = new JsonObject();
      left.addProperty("value", 1);
      JsonObject right = new JsonObject();
      right.addProperty("value", 2);
      String leftId = store.capture("left", "tool", left).get("snapshot_id").getAsString();
      String rightId = store.capture("right", "tool", right).get("snapshot_id").getAsString();
      JsonObject difference = store.compare(leftId, rightId);
      check(!difference.get("equal").getAsBoolean(), "different snapshots should not be equal");
      check(difference.get("difference_count").getAsInt() == 1, "one JSON path should differ");
      check("/value".equals(difference.getAsJsonArray("differences").get(0).getAsJsonObject().get("path").getAsString()), "difference path should be stable");
      check(store.delete(leftId).get("deleted").getAsBoolean(), "snapshot deletion should succeed");
   }

   private static JsonObject assertion(String path, String operator, String expected) {
      JsonObject result = new JsonObject();
      result.addProperty("path", path);
      result.addProperty("operator", operator);
      result.addProperty("expected", expected);
      return result;
   }

   private static JsonObject assertion(String path, String operator, int expected) {
      JsonObject result = new JsonObject();
      result.addProperty("path", path);
      result.addProperty("operator", operator);
      result.addProperty("expected", expected);
      return result;
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
