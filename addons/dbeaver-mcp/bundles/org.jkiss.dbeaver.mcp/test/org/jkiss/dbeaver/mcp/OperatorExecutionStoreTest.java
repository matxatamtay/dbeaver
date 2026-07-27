package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;

public final class OperatorExecutionStoreTest {
    public static void main(String[] args) {
        DBeaverExecutionStore store = new DBeaverExecutionStore();
        DBeaverExecutionStore.ExecutionRequest request = new DBeaverExecutionStore.ExecutionRequest(
            "editor-1", "connection-1", "General", "select 1", 200, 30, true, true, Instant.now()
        );
        JsonObject approval = store.createApproval(request);
        String approvalId = approval.get("approval_id").getAsString();
        check(store.consumeApproval(approvalId) == request, "approval must return the bound request");
        boolean replayRejected = false;
        try {
            store.consumeApproval(approvalId);
        } catch (IllegalArgumentException expected) {
            replayRejected = true;
        }
        check(replayRejected, "approval must be one-time");

        JsonObject raw = new JsonObject();
        raw.addProperty("has_result_set", true);
        JsonArray columns = new JsonArray();
        JsonObject column = new JsonObject();
        column.addProperty("name", "answer");
        columns.add(column);
        raw.add("columns", columns);
        JsonArray rows = new JsonArray();
        for (int index = 0; index < 5; index++) {
            JsonObject row = new JsonObject();
            row.addProperty("answer", index);
            rows.add(row);
        }
        raw.add("rows", rows);
        raw.addProperty("row_count", rows.size());
        JsonObject summary = store.storeResult(request, raw);
        check(summary.get("rows_available").getAsInt() == 5, "summary must expose bounded row count");
        check(summary.getAsJsonArray("preview_rows").size() == 5, "small results should fit in preview");
        JsonObject page = store.fetchResult(summary.get("execution_id").getAsString(), 2, 2);
        check(page.getAsJsonArray("rows").size() == 2, "second page should contain two rows");
        check(page.getAsJsonArray("rows").get(0).getAsJsonObject().get("answer").getAsInt() == 2, "paging offset must be stable");
        check(store.queryHistory(10).get("count").getAsInt() == 1, "history should include the execution");
        System.out.println("OperatorExecutionStoreTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
