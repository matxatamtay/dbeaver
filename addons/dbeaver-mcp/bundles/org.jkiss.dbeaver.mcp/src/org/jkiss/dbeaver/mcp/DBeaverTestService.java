/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class DBeaverTestService {
   private static final int MAX_CASES = 50;
   private static final Set<String> FORBIDDEN_TARGETS = Set.of("dbeaver_test", "dbeaver_job");

   private final McpToolRegistry registry;
   private final DBeaverTestStore store = new DBeaverTestStore();

   DBeaverTestService(McpToolRegistry registry) {
      this.registry = registry;
   }

   JsonObject execute(String action, JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      return switch (action) {
         case "validate_case" -> validateCase(arguments);
         case "assert_json" -> assertJson(arguments);
         case "run_case" -> runCase(arguments, null);
         case "run_suite" -> submitSuite(arguments, jobs);
         case "wait_for" -> submitWait(arguments, jobs);
         case "capture_snapshot" -> captureSnapshot(arguments);
         case "list_snapshots" -> this.store.list();
         case "get_snapshot" -> this.store.get(McpJson.requiredString(arguments, "snapshot_id"));
         case "delete_snapshot" -> this.store.delete(McpJson.requiredString(arguments, "snapshot_id"));
         case "compare_snapshots" -> this.store.compare(
            McpJson.requiredString(arguments, "left_snapshot_id"),
            McpJson.requiredString(arguments, "right_snapshot_id")
         );
         case "schema_drift" -> schemaDrift(arguments);
         case "migration_rehearsal" -> submitMigrationRehearsal(arguments, jobs);
         default -> throw new IllegalArgumentException("Unknown tester action: " + action);
      };
   }

   private JsonObject validateCase(JsonObject arguments) throws Exception {
      JsonObject testCase = caseObject(arguments);
      String tool = validateTarget(testCase);
      JsonArray assertions = DBeaverAssertionEngine.array(testCase, "assertions");
      JsonObject descriptor = this.registry.describeTool(tool);
      JsonObject result = new JsonObject();
      result.addProperty("valid", true);
      result.addProperty("name", caseName(testCase));
      result.addProperty("tool", tool);
      result.add("descriptor", descriptor);
      result.addProperty("assertion_count", assertions.size());
      result.addProperty("requires_allow_non_read_only", !isReadOnly(descriptor));
      result.addProperty("destructive_hint", descriptor.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
      return result;
   }

   private JsonObject assertJson(JsonObject arguments) {
      JsonElement value = arguments.has("value") ? arguments.get("value") : new JsonObject();
      return DBeaverAssertionEngine.evaluate(value, DBeaverAssertionEngine.array(arguments, "assertions"));
   }

   private JsonObject runCase(JsonObject arguments, DBeaverMcpJobManager.JobContext jobContext) throws Exception {
      JsonObject testCase = caseObject(arguments);
      String tool = validateTarget(testCase);
      JsonObject descriptor = this.registry.describeTool(tool);
      boolean readOnly = isReadOnly(descriptor);
      boolean allowNonReadOnly = McpJson.getBoolean(testCase, "allow_non_read_only", false);
      if (!readOnly && !allowNonReadOnly) {
         throw new IllegalArgumentException(
            "Target tool is not declared read-only. Set allow_non_read_only=true only after reviewing its action and native confirmation behavior: " + tool
         );
      }
      int attempts = McpJson.getInt(testCase, "attempts", 1, 1, 6);
      int delayMs = McpJson.getInt(testCase, "retry_delay_ms", 0, 0, 10000);
      JsonArray assertions = DBeaverAssertionEngine.array(testCase, "assertions");
      JsonObject forwarded = McpJson.getObject(testCase, "arguments");
      JsonObject finalResult = null;
      JsonObject finalAssertions = null;
      JsonArray attemptResults = new JsonArray();
      Exception finalError = null;
      long startedAt = System.nanoTime();

      for (int attempt = 1; attempt <= attempts; attempt++) {
         checkCancelled(jobContext);
         JsonObject attemptPayload = new JsonObject();
         attemptPayload.addProperty("attempt", attempt);
         attemptPayload.addProperty("started_at", Instant.now().toString());
         try {
            JsonObject result = this.registry.executeRaw(tool, forwarded.deepCopy());
            JsonObject assertionResult = DBeaverAssertionEngine.evaluate(result, assertions);
            attemptPayload.addProperty("executed", true);
            attemptPayload.addProperty("passed", assertionResult.get("passed").getAsBoolean());
            attemptPayload.add("assertion_summary", compactAssertionSummary(assertionResult));
            finalResult = result;
            finalAssertions = assertionResult;
            finalError = null;
            attemptResults.add(attemptPayload);
            if (assertionResult.get("passed").getAsBoolean()) break;
         } catch (Exception e) {
            finalError = e;
            attemptPayload.addProperty("executed", false);
            attemptPayload.addProperty("passed", false);
            attemptPayload.addProperty("error", McpJson.safeMessage(e));
            attemptResults.add(attemptPayload);
         }
         if (attempt < attempts && delayMs > 0) {
            sleep(delayMs, jobContext);
         }
      }

      JsonObject report = new JsonObject();
      report.addProperty("name", caseName(testCase));
      report.addProperty("tool", tool);
      report.addProperty("read_only_hint", readOnly);
      report.addProperty("attempt_count", attemptResults.size());
      report.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000.0);
      report.add("attempts", attemptResults);
      if (finalResult != null) report.add("result", boundedResult(finalResult));
      if (finalAssertions != null) report.add("assertions", finalAssertions.deepCopy());
      boolean passed = finalError == null && finalAssertions != null && finalAssertions.get("passed").getAsBoolean();
      report.addProperty("passed", passed);
      if (finalError != null) report.addProperty("error", McpJson.safeMessage(finalError));
      return report;
   }

   private JsonObject submitSuite(JsonObject arguments, DBeaverMcpJobManager jobs) {
      List<JsonObject> cases = DBeaverAssertionEngine.objectList(arguments, "cases", MAX_CASES);
      if (cases.isEmpty()) throw new IllegalArgumentException("cases must contain at least one test case");
      boolean failFast = McpJson.getBoolean(arguments, "fail_fast", false);
      String name = McpJson.getString(arguments, "name", "DBeaver MCP test suite");
      String jobId = jobs.submit("tester-platform", "test-suite", true, context -> {
         JsonArray reports = new JsonArray();
         int passed = 0;
         for (JsonObject testCase : cases) {
            context.checkCancelled();
            JsonObject report = runCase(testCase, context);
            reports.add(boundedCaseReport(report));
            if (report.get("passed").getAsBoolean()) passed++;
            else if (failFast) break;
         }
         JsonObject result = new JsonObject();
         result.addProperty("name", McpJson.truncate(name));
         result.addProperty("passed", passed == reports.size() && reports.size() == cases.size());
         result.addProperty("case_count", cases.size());
         result.addProperty("executed_count", reports.size());
         result.addProperty("passed_count", passed);
         result.addProperty("failed_count", reports.size() - passed);
         result.addProperty("fail_fast", failFast);
         result.add("cases", reports);
         return result;
      });
      return jobPayload(jobId, "test-suite");
   }

   private JsonObject submitWait(JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      JsonObject testCase = caseObject(arguments);
      String tool = validateTarget(testCase);
      JsonObject descriptor = this.registry.describeTool(tool);
      if (!isReadOnly(descriptor)) {
         throw new IllegalArgumentException("wait_for only accepts tools declared read-only: " + tool);
      }
      int attempts = McpJson.getInt(arguments, "max_attempts", 20, 1, 60);
      int delayMs = McpJson.getInt(arguments, "delay_ms", 1000, 0, 10000);
      JsonObject normalized = testCase.deepCopy();
      normalized.addProperty("attempts", 1);
      normalized.addProperty("retry_delay_ms", 0);
      String jobId = jobs.submit("tester-platform", "wait-for-condition", true, context -> {
         JsonArray observations = new JsonArray();
         JsonObject last = null;
         for (int attempt = 1; attempt <= attempts; attempt++) {
            context.checkCancelled();
            last = runCase(normalized, context);
            JsonObject observation = new JsonObject();
            observation.addProperty("attempt", attempt);
            observation.addProperty("passed", last.get("passed").getAsBoolean());
            observation.addProperty("elapsed_ms", last.get("elapsed_ms").getAsDouble());
            if (last.has("error")) observation.add("error", last.get("error").deepCopy());
            observations.add(observation);
            if (last.get("passed").getAsBoolean()) break;
            if (attempt < attempts) sleep(delayMs, context);
         }
         JsonObject result = new JsonObject();
         result.addProperty("passed", last != null && last.get("passed").getAsBoolean());
         result.addProperty("attempt_count", observations.size());
         result.add("observations", observations);
         if (last != null) result.add("last_case", boundedCaseReport(last));
         return result;
      });
      return jobPayload(jobId, "wait-for-condition");
   }

   private JsonObject captureSnapshot(JsonObject arguments) throws Exception {
      String tool = validateTarget(arguments);
      JsonObject descriptor = this.registry.describeTool(tool);
      if (!isReadOnly(descriptor) && !McpJson.getBoolean(arguments, "allow_non_read_only", false)) {
         throw new IllegalArgumentException("Snapshot targets must be read-only unless allow_non_read_only=true is explicitly set");
      }
      JsonObject payload = this.registry.executeRaw(tool, McpJson.getObject(arguments, "arguments"));
      JsonObject result = this.store.capture(McpJson.getString(arguments, "name", tool), tool, payload);
      result.addProperty("captured", true);
      return result;
   }

   private JsonObject schemaDrift(JsonObject arguments) throws Exception {
      JsonObject comparison = this.registry.executeRaw("dbeaver_compare_schemas", arguments.deepCopy());
      int maximumAdded = McpJson.getInt(arguments, "max_added", 0, 0, 10000);
      int maximumRemoved = McpJson.getInt(arguments, "max_removed", 0, 0, 10000);
      int maximumChanged = McpJson.getInt(arguments, "max_changed", 0, 0, 10000);
      int added = comparison.get("added_count").getAsInt();
      int removed = comparison.get("removed_count").getAsInt();
      int changed = comparison.get("changed_count").getAsInt();
      JsonObject result = new JsonObject();
      result.addProperty("passed", added <= maximumAdded && removed <= maximumRemoved && changed <= maximumChanged);
      JsonObject thresholds = new JsonObject();
      thresholds.addProperty("max_added", maximumAdded);
      thresholds.addProperty("max_removed", maximumRemoved);
      thresholds.addProperty("max_changed", maximumChanged);
      result.add("thresholds", thresholds);
      result.add("comparison", comparison);
      return result;
   }

   private JsonObject submitMigrationRehearsal(JsonObject arguments, DBeaverMcpJobManager jobs) {
      JsonObject analysisArguments = McpJson.getObject(arguments, "analysis");
      if (analysisArguments.isEmpty()) throw new IllegalArgumentException("analysis arguments are required");
      JsonObject simulationArguments = McpJson.getObject(arguments, "simulation");
      boolean allowSimulation = McpJson.getBoolean(arguments, "allow_simulation", false);
      List<JsonObject> postChecks = DBeaverAssertionEngine.objectList(arguments, "post_checks", 20);
      JsonArray reportAssertions = DBeaverAssertionEngine.array(arguments, "assertions");
      if (!simulationArguments.isEmpty() && !allowSimulation) {
         throw new IllegalArgumentException("Set allow_simulation=true after reviewing simulation SQL and rollback limitations");
      }
      String jobId = jobs.submit("tester-platform", "migration-rehearsal", true, context -> {
         context.checkCancelled();
         JsonObject result = new JsonObject();
         JsonObject analysis = this.registry.executeRaw("dbeaver_analyze_change", analysisArguments.deepCopy());
         result.add("analysis", analysis);
         if (!simulationArguments.isEmpty()) {
            context.checkCancelled();
            result.add("simulation", this.registry.executeRaw("dbeaver_simulate_change", simulationArguments.deepCopy()));
         }
         JsonArray checks = new JsonArray();
         int passedChecks = 0;
         for (JsonObject check : postChecks) {
            context.checkCancelled();
            JsonObject report = runCase(check, context);
            checks.add(boundedCaseReport(report));
            if (report.get("passed").getAsBoolean()) passedChecks++;
         }
         result.addProperty("post_check_count", postChecks.size());
         result.addProperty("post_check_passed_count", passedChecks);
         result.add("post_checks", checks);
         JsonObject assertionResult = DBeaverAssertionEngine.evaluate(result, reportAssertions);
         result.add("assertions", assertionResult);
         result.addProperty("passed", passedChecks == postChecks.size() && assertionResult.get("passed").getAsBoolean());
         return result;
      });
      return jobPayload(jobId, "migration-rehearsal");
   }

   private String validateTarget(JsonObject testCase) throws Exception {
      String tool = McpJson.requiredString(testCase, "tool");
      if (FORBIDDEN_TARGETS.contains(tool)) {
         throw new IllegalArgumentException("Tester recursion is not allowed: " + tool);
      }
      this.registry.describeTool(tool);
      return tool;
   }

   private static JsonObject caseObject(JsonObject arguments) {
      JsonObject nested = McpJson.getObject(arguments, "case");
      return nested.isEmpty() ? arguments : nested;
   }

   private static boolean isReadOnly(JsonObject descriptor) {
      JsonObject annotations = descriptor.getAsJsonObject("annotations");
      return annotations != null && annotations.has("readOnlyHint") && annotations.get("readOnlyHint").getAsBoolean();
   }

   private static String caseName(JsonObject testCase) {
      return McpJson.truncate(McpJson.getString(testCase, "name", McpJson.getString(testCase, "tool", "test case")));
   }

   private static JsonObject boundedCaseReport(JsonObject report) {
      String json = McpJson.GSON.toJson(report);
      if (json.length() <= 32768) return report.deepCopy();
      JsonObject bounded = new JsonObject();
      for (String key : List.of("name", "tool", "passed", "read_only_hint", "attempt_count", "elapsed_ms", "error")) {
         if (report.has(key)) bounded.add(key, report.get(key).deepCopy());
      }
      bounded.addProperty("details_truncated", true);
      bounded.addProperty("original_chars", json.length());
      return bounded;
   }

   private static JsonElement boundedResult(JsonObject result) {
      String json = McpJson.GSON.toJson(result);
      if (json.length() <= 65536) return result.deepCopy();
      JsonObject bounded = new JsonObject();
      bounded.addProperty("truncated", true);
      bounded.addProperty("original_chars", json.length());
      bounded.addProperty("preview", json.substring(0, 16384));
      return bounded;
   }

   private static JsonObject compactAssertionSummary(JsonObject assertions) {
      JsonObject result = new JsonObject();
      for (String key : List.of("passed", "assertion_count", "passed_count", "failed_count")) {
         if (assertions.has(key)) result.add(key, assertions.get(key).deepCopy());
      }
      return result;
   }

   private static JsonObject jobPayload(String jobId, String type) {
      JsonObject result = new JsonObject();
      result.addProperty("job_id", jobId);
      result.addProperty("type", type);
      result.addProperty("submitted", true);
      return result;
   }

   private static void checkCancelled(DBeaverMcpJobManager.JobContext context) throws InterruptedException {
      if (context != null) context.checkCancelled();
   }

   private static void sleep(int delayMs, DBeaverMcpJobManager.JobContext context) throws InterruptedException {
      int remaining = delayMs;
      while (remaining > 0) {
         checkCancelled(context);
         int step = Math.min(remaining, 250);
         Thread.sleep(step);
         remaining -= step;
      }
   }
}
