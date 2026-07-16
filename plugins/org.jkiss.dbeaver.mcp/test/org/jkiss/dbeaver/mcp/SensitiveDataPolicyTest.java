package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class SensitiveDataPolicyTest {
    public static void main(String[] args) {
        check("password".equals(SensitiveDataPolicy.classify("password_hash", "varchar")), "password classification");
        check("token".equals(SensitiveDataPolicy.classify("api_key", "text")), "token classification");
        check("email".equals(SensitiveDataPolicy.classify("email", "citext")), "email classification");
        check("binary_data".equals(SensitiveDataPolicy.classify("payload", "bytea binary")), "binary classification");

        JsonObject payload = payload();
        JsonObject minimallyMasked = SensitiveDataPolicy.maskQueryPayload(payload, false);
        JsonObject row = minimallyMasked.getAsJsonArray("rows").get(0).getAsJsonObject();
        check("<masked:token>".equals(row.get("api_key").getAsString()), "tokens must always be masked");
        check("person@example.com".equals(row.get("email").getAsString()), "email may remain visible when masking is disabled");

        JsonObject fullyMasked = SensitiveDataPolicy.maskQueryPayload(payload, true);
        JsonObject maskedRow = fullyMasked.getAsJsonArray("rows").get(0).getAsJsonObject();
        check("<masked:email>".equals(maskedRow.get("email").getAsString()), "email should be masked by default policy");
        System.out.println("SensitiveDataPolicyTest passed");
    }

    private static JsonObject payload() {
        JsonObject payload = new JsonObject();
        JsonArray columns = new JsonArray();
        columns.add(column("api_key", "text"));
        columns.add(column("email", "varchar"));
        payload.add("columns", columns);
        JsonObject row = new JsonObject();
        row.addProperty("api_key", "secret-value");
        row.addProperty("email", "person@example.com");
        JsonArray rows = new JsonArray();
        rows.add(row);
        payload.add("rows", rows);
        return payload;
    }

    private static JsonObject column(String name, String type) {
        JsonObject column = new JsonObject();
        column.addProperty("name", name);
        column.addProperty("label", name);
        column.addProperty("type", type);
        return column;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
