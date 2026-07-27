package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class TriggerDefinitionParserTest {
    public static void main(String[] args) {
        JsonObject parsed = TriggerDefinitionParser.parse("""
            CREATE TRIGGER trg_order_change
            AFTER UPDATE OF status, total_amount ON sales.orders
            FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
            EXECUTE FUNCTION audit.log_order_change()
            """);
        check("after".equals(parsed.get("timing").getAsString()), "timing");
        check("row".equals(parsed.get("level").getAsString()), "level");
        check(contains(parsed.getAsJsonArray("events"), "update"), "UPDATE event");
        check(contains(parsed.getAsJsonArray("update_columns"), "status"), "status column");
        check(contains(parsed.getAsJsonArray("update_columns"), "total_amount"), "total_amount column");
        check(parsed.get("condition").getAsString().contains("OLD.status"), "WHEN condition");
        System.out.println("TriggerDefinitionParserTest passed");
    }

    private static boolean contains(JsonArray values, String expected) {
        return values.asList().stream().anyMatch(value -> expected.equals(value.getAsString()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
