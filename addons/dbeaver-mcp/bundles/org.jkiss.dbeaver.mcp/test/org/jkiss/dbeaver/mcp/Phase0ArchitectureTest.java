package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Phase0ArchitectureTest {
    public static void main(String[] args) throws Exception {
        testPolicyParsing();
        testToolDefinitionCopiesInputs();
        testJobLifecycle();
        System.out.println("Phase0ArchitectureTest passed");
    }

    private static void testPolicyParsing() {
        DBeaverMcpPolicy policy = DBeaverMcpPolicy.parse("observe, query, workspace");
        check(policy.allows(Set.of(DBeaverMcpScope.OBSERVE)), "observe scope should be allowed");
        check(policy.allows(Set.of(DBeaverMcpScope.QUERY, DBeaverMcpScope.WORKSPACE)), "combined allowed scopes should pass");
        check(!policy.allows(Set.of(DBeaverMcpScope.DATA_WRITE)), "data-write scope should be denied");
        check(DBeaverMcpPolicy.parse("all").allowedScopes().size() == DBeaverMcpScope.values().length, "all should enable every scope");

        boolean invalidRejected = false;
        try {
            DBeaverMcpPolicy.parse("observe,unknown");
        } catch (IllegalArgumentException expected) {
            invalidRejected = true;
        }
        check(invalidRejected, "unknown scopes must fail closed");
    }

    private static void testToolDefinitionCopiesInputs() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        DBeaverMcpToolDefinition definition = new DBeaverMcpToolDefinition(
            "example_tool",
            "Example tool.",
            schema,
            Set.of(DBeaverMcpScope.OBSERVE),
            true,
            false,
            true,
            arguments -> new JsonObject()
        );
        schema.addProperty("mutated", true);
        check(!definition.inputSchema().has("mutated"), "tool definition must copy caller-owned schemas");
    }

    private static void testJobLifecycle() throws Exception {
        try (DBeaverMcpJobManager jobs = new DBeaverMcpJobManager()) {
            String successId = jobs.submit("test", "success", true, context -> {
                JsonObject result = new JsonObject();
                result.addProperty("answer", 42);
                return result;
            });
            JsonObject success = awaitTerminal(jobs, successId);
            check("succeeded".equals(success.get("state").getAsString()), "job should succeed");
            check(success.getAsJsonObject("result").get("answer").getAsInt() == 42, "job result should be retained");

            AtomicBoolean cleanupFinished = new AtomicBoolean();
            String cancelId = jobs.submit("test", "cancel", true, context -> {
                try {
                    while (true) {
                        context.checkCancelled();
                        Thread.sleep(5L);
                    }
                } finally {
                    // Future.cancel(true) sets the interrupt flag. Real jobs clear it before bounded cleanup.
                    Thread.interrupted();
                    Thread.sleep(30L);
                    cleanupFinished.set(true);
                }
            });
            awaitRunning(jobs, cancelId);
            jobs.cancel(cancelId);
            JsonObject cancelled = awaitTerminal(jobs, cancelId);
            check("cancelled".equals(cancelled.get("state").getAsString()), "cancelled job should reach terminal state");
            check(cleanupFinished.get(), "cancelled job must not report terminal before cleanup completes");
            check(jobs.list(10).get("count").getAsInt() == 2, "job list should include both jobs");
        }
    }

    private static void awaitRunning(DBeaverMcpJobManager jobs, String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            String state = jobs.get(jobId, false).get("state").getAsString();
            if ("running".equals(state)) return;
            if (Set.of("failed", "cancelled", "succeeded").contains(state)) {
                throw new AssertionError("job terminated before cancellation test: " + state);
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("job did not start: " + jobId);
    }

    private static JsonObject awaitTerminal(DBeaverMcpJobManager jobs, String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            JsonObject state = jobs.get(jobId, true);
            String value = state.get("state").getAsString();
            if (Set.of("succeeded", "failed", "cancelled").contains(value)) {
                return state;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("job did not reach a terminal state: " + jobId);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
