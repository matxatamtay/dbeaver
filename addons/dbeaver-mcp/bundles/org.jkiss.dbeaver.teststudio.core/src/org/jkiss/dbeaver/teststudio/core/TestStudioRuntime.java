package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import org.jkiss.dbeaver.mcp.DBeaverMcpContext;
import org.jkiss.dbeaver.teststudio.api.TestStudioService;
import org.jkiss.dbeaver.teststudio.spi.TestStudioAiProvider;

final class TestStudioRuntime implements TestStudioService {
   private static final String VERSION = "2.0.1";
   private final DBeaverMcpContext mcp;
   private final TestPlanValidator validator = new TestPlanValidator();
   private final TestPlanMigrator migrator = new TestPlanMigrator();
   private final TestPlanStore plans = new TestPlanStore(validator);
   private final VariableResolver variables = new VariableResolver();
   private final StudioExtensionRegistry extensions = new StudioExtensionRegistry();
   private final AssertionRegistry assertions = new AssertionRegistry();
   private final RunEvidenceStore evidence = new RunEvidenceStore();
   private final ReportRegistry reports = new ReportRegistry();
   private final StudioRunService runs;

   TestStudioRuntime(DBeaverMcpContext mcp) {
      this.mcp = mcp;
      this.runs = mcp == null ? null : new StudioRunService(
         mcp,
         plans,
         validator,
         variables,
         extensions,
         assertions,
         evidence,
         reports
      );
   }

   @Override
   public JsonObject capabilities() {
      JsonObject result = new JsonObject();
      result.addProperty("name", "AI Database Test Studio");
      result.addProperty("version", VERSION);
      result.addProperty("schema_version", TestPlanValidator.CURRENT_VERSION);
      result.addProperty("engine", true);
      result.addProperty("runner", runs != null);
      result.addProperty("mcp_attached", mcp != null);
      result.addProperty("source_repository", "dbeaver-mcp");
      result.addProperty("build_strategy", "additive_overlay");
      result.addProperty("dbeaver_upstream_tracked_diff_required", false);
      result.add("extensions", extensions.capabilities());
      result.add("assertions", assertions.describe());
      result.add("reports", reports.describe());
      JsonObject limits = new JsonObject();
      limits.addProperty("plan_bytes", 1024 * 1024);
      limits.addProperty("fixture_bytes", 10 * 1024 * 1024);
      limits.addProperty("fixture_rows", 10000);
      limits.addProperty("run_evidence_bytes", RunEvidenceStore.MAX_RUN_BYTES);
      limits.addProperty("attachment_bytes", RunEvidenceStore.MAX_ATTACHMENT_BYTES);
      limits.addProperty("snapshots_per_run", 25);
      result.add("limits", limits);
      return result;
   }

   JsonObject validatePlan(JsonObject arguments) {
      return validator.validate(requiredPlan(arguments));
   }

   JsonObject migratePlan(JsonObject arguments) throws Exception {
      JsonObject migrated = migrator.migrate(requiredPlan(arguments));
      JsonObject validation = validator.validate(migrated.getAsJsonObject("plan"));
      migrated.add("validation", validation);
      if (StudioJson.bool(arguments, "save", false)) {
         if (!validation.get("valid").getAsBoolean()) throw new IllegalArgumentException("Migrated plan is invalid and was not saved");
         migrated.add("save_result", plans.save(
            StudioJson.required(arguments, "project"),
            migrated.getAsJsonObject("plan"),
            StudioJson.bool(arguments, "overwrite", false)
         ));
      }
      return migrated;
   }

   @Override
   public JsonObject listPlans(String project) throws Exception {
      return plans.list(project);
   }

   @Override
   public JsonObject getPlan(String project, String planId) throws Exception {
      return plans.get(project, planId);
   }

   @Override
   public JsonObject savePlan(String project, JsonObject plan, boolean overwrite) throws Exception {
      JsonObject copy = plan.deepCopy();
      if (!copy.has("created_at")) copy.addProperty("created_at", StudioJson.now());
      copy.addProperty("updated_at", StudioJson.now());
      return plans.save(project, copy, overwrite);
   }

   JsonObject clonePlan(JsonObject arguments) throws Exception {
      return plans.clonePlan(
         StudioJson.required(arguments, "project"),
         StudioJson.required(arguments, "plan_id"),
         StudioJson.required(arguments, "new_id"),
         StudioJson.required(arguments, "new_name")
      );
   }

   JsonObject deletePlan(JsonObject arguments) throws Exception {
      return plans.delete(StudioJson.required(arguments, "project"), StudioJson.required(arguments, "plan_id"));
   }

   JsonObject planRun(JsonObject arguments) throws Exception {
      requireRunner();
      return runs.planRun(arguments);
   }

   JsonObject approveRun(JsonObject arguments) throws Exception {
      requireRunner();
      return runs.approveRun(arguments);
   }

   JsonObject cancelApproval(JsonObject arguments) {
      requireRunner();
      return runs.cancelApproval(arguments);
   }

   boolean approvalRequiresDataWrite(String approvalId) {
      requireRunner();
      return runs.approvalRequiresDataWrite(approvalId);
   }

   JsonObject runPlan(JsonObject arguments) {
      requireRunner();
      return runs.runPlan(arguments);
   }

   JsonObject runStep(JsonObject arguments) throws Exception {
      String project = StudioJson.required(arguments, "project");
      JsonObject plan = arguments.has("plan") && arguments.get("plan").isJsonObject()
         ? arguments.getAsJsonObject("plan").deepCopy()
         : plans.get(project, StudioJson.required(arguments, "plan_id")).getAsJsonObject("plan");
      String stepId = StudioJson.required(arguments, "step_id");
      JsonObject selected = null;
      for (JsonElement item : StudioJson.array(plan, "steps")) {
         JsonObject step = item.getAsJsonObject();
         if (stepId.equals(StudioJson.string(step, "id", ""))) {
            selected = step.deepCopy();
            break;
         }
      }
      if (selected == null) throw new IllegalArgumentException("Plan step not found: " + stepId);
      JsonArray one = new JsonArray();
      one.add(selected);
      plan.add("setup", new JsonArray());
      plan.add("steps", one);
      plan.add("cleanup", StudioJson.bool(arguments, "include_cleanup", true) ? StudioJson.array(plan, "cleanup") : new JsonArray());
      plan.addProperty("id", StudioJson.string(plan, "id", "plan") + "-step-" + stepId.replaceAll("[^A-Za-z0-9._-]", "_"));
      plan.addProperty("name", StudioJson.string(plan, "name", "Plan") + " — step " + stepId);
      JsonObject request = new JsonObject();
      request.addProperty("project", project);
      request.add("plan", plan);
      return planRun(request);
   }

   JsonObject cleanupRun(JsonObject arguments) throws Exception {
      String project = StudioJson.required(arguments, "project");
      String runId = StudioJson.required(arguments, "run_id");
      JsonObject plan = evidence.getPlan(project, runId);
      JsonArray cleanup = StudioJson.array(plan, "cleanup");
      if (cleanup.isEmpty()) throw new IllegalArgumentException("Run plan contains no explicit cleanup steps");
      plan.add("setup", new JsonArray());
      plan.add("steps", cleanup.deepCopy());
      plan.add("cleanup", new JsonArray());
      plan.addProperty("id", StudioJson.string(plan, "id", "plan") + "-cleanup");
      plan.addProperty("name", StudioJson.string(plan, "name", "Plan") + " — cleanup only");
      JsonObject request = new JsonObject();
      request.addProperty("project", project);
      request.add("plan", plan);
      JsonObject result = planRun(request);
      result.addProperty("source_run_id", runId);
      return result;
   }

   JsonObject retryFailed(JsonObject arguments) throws Exception {
      requireRunner();
      return runs.retryFailed(arguments);
   }

   JsonObject runStatus(JsonObject arguments, boolean includeResult) {
      requireMcp();
      return mcp.jobs().get(StudioJson.required(arguments, "job_id"), includeResult);
   }

   JsonObject cancelRun(JsonObject arguments) {
      requireMcp();
      return mcp.jobs().cancel(StudioJson.required(arguments, "job_id"));
   }

   @Override
   public JsonObject listRuns(String project, int limit) {
      return evidence.list(project, Math.max(1, Math.min(limit, 500)));
   }

   @Override
   public JsonObject getRun(String project, String runId) {
      return evidence.get(project, runId);
   }

   JsonObject deleteRun(JsonObject arguments) {
      return evidence.delete(StudioJson.required(arguments, "project"), StudioJson.required(arguments, "run_id"));
   }

   JsonObject pinRun(JsonObject arguments) {
      return evidence.pin(
         StudioJson.required(arguments, "project"),
         StudioJson.required(arguments, "run_id"),
         StudioJson.bool(arguments, "pinned", true)
      );
   }

   JsonObject cleanupRuns(JsonObject arguments) {
      return evidence.cleanup(
         StudioJson.required(arguments, "project"),
         StudioJson.integer(arguments, "keep_last", 20, 0, 1000),
         StudioJson.integer(arguments, "older_than_days", 30, 1, 3650)
      );
   }

   JsonObject generateReport(JsonObject arguments) {
      return reports.generate(
         evidence,
         StudioJson.required(arguments, "project"),
         StudioJson.required(arguments, "run_id"),
         StudioJson.required(arguments, "format"),
         StudioJson.object(arguments, "options"),
         StudioJson.bool(arguments, "overwrite", false)
      );
   }

   JsonObject listReports(JsonObject arguments) {
      return evidence.listReports(StudioJson.required(arguments, "project"), StudioJson.required(arguments, "run_id"));
   }

   JsonObject getReport(JsonObject arguments) {
      String content = evidence.readReport(
         StudioJson.required(arguments, "project"),
         StudioJson.required(arguments, "run_id"),
         StudioJson.required(arguments, "name")
      );
      JsonObject result = new JsonObject();
      result.addProperty("name", StudioJson.required(arguments, "name"));
      result.addProperty("content", content);
      result.addProperty("chars", content.length());
      return result;
   }


   JsonObject deleteReport(JsonObject arguments) {
      return evidence.deleteReport(
         StudioJson.required(arguments, "project"),
         StudioJson.required(arguments, "run_id"),
         StudioJson.required(arguments, "name")
      );
   }

   JsonObject explainPlan(JsonObject arguments) {
      JsonObject plan = requiredPlan(arguments);
      JsonObject validation = validator.validate(plan);
      JsonObject result = new JsonObject();
      result.addProperty("id", StudioJson.string(plan, "id", ""));
      result.addProperty("name", StudioJson.string(plan, "name", ""));
      result.addProperty("schema_version", StudioJson.string(plan, "schema_version", ""));
      result.addProperty("target_count", StudioJson.object(plan, "targets").size());
      result.addProperty("setup_steps", StudioJson.array(plan, "setup").size());
      result.addProperty("test_steps", StudioJson.array(plan, "steps").size());
      result.addProperty("cleanup_steps", StudioJson.array(plan, "cleanup").size());
      result.addProperty("sandbox", StudioJson.string(StudioJson.object(plan, "policy"), "sandbox", "transaction"));
      result.addProperty("commit_on_success", StudioJson.bool(StudioJson.object(plan, "policy"), "commit_on_success", false));
      result.add("validation", validation);
      JsonArray flow = new JsonArray();
      for (String section : List.of("setup", "steps", "cleanup")) {
         int index = 0;
         for (JsonElement item : StudioJson.array(plan, section)) {
            JsonObject step = item.getAsJsonObject();
            JsonObject summary = new JsonObject();
            summary.addProperty("section", section);
            summary.addProperty("index", ++index);
            summary.addProperty("id", StudioJson.string(step, "id", section + "-" + index));
            summary.addProperty("type", StudioJson.string(step, "type", ""));
            summary.addProperty("target", StudioJson.string(step, "target", "default"));
            summary.addProperty("assertion_count", StudioJson.array(step, "assertions").size());
            flow.add(summary);
         }
      }
      result.add("flow", flow);
      return result;
   }

   JsonObject generateAssertions(JsonObject arguments) throws Exception {
      return transformPlan(arguments, "Generate stronger database assertions for this plan. Preserve safety, targets, and cleanup semantics. ");
   }

   JsonObject suggestFixtures(JsonObject arguments) throws Exception {
      return transformPlan(arguments, "Suggest minimal deterministic fixtures for this plan. Avoid real personal or production data. ");
   }

   JsonObject suggestCleanup(JsonObject arguments) throws Exception {
      return transformPlan(arguments, "Suggest reliable always-run cleanup steps for this plan. Prefer rollback-safe cleanup. ");
   }

   JsonObject compareRuns(JsonObject arguments) {
      String project = StudioJson.required(arguments, "project");
      JsonObject left = evidence.get(project, StudioJson.required(arguments, "left_run_id"));
      JsonObject right = evidence.get(project, StudioJson.required(arguments, "right_run_id"));
      JsonObject leftReport = left.getAsJsonObject("report");
      JsonObject rightReport = right.getAsJsonObject("report");
      if (leftReport == null || rightReport == null) throw new IllegalArgumentException("Both runs require canonical reports");
      JsonObject result = new JsonObject();
      result.addProperty("left_run_id", StudioJson.required(arguments, "left_run_id"));
      result.addProperty("right_run_id", StudioJson.required(arguments, "right_run_id"));
      result.addProperty("left_state", StudioJson.string(leftReport, "state", ""));
      result.addProperty("right_state", StudioJson.string(rightReport, "state", ""));
      result.addProperty("same_result", StudioJson.bool(leftReport, "passed", false) == StudioJson.bool(rightReport, "passed", false));
      result.addProperty("left_fingerprint", StudioJson.fingerprint(leftReport));
      result.addProperty("right_fingerprint", StudioJson.fingerprint(rightReport));
      Map<String, JsonObject> leftSteps = stepsById(leftReport);
      Map<String, JsonObject> rightSteps = stepsById(rightReport);
      JsonArray changes = new JsonArray();
      Set<String> ids = new TreeSet<>();
      ids.addAll(leftSteps.keySet());
      ids.addAll(rightSteps.keySet());
      for (String id : ids) {
         JsonObject a = leftSteps.get(id), b = rightSteps.get(id);
         if (a == null || b == null || !StudioJson.canonical(a).equals(StudioJson.canonical(b))) {
            JsonObject change = new JsonObject();
            change.addProperty("step_id", id);
            change.addProperty("left_status", a == null ? "missing" : StudioJson.string(a, "status", ""));
            change.addProperty("right_status", b == null ? "missing" : StudioJson.string(b, "status", ""));
            if (a != null && a.has("elapsed_ms")) change.addProperty("left_elapsed_ms", a.get("elapsed_ms").getAsDouble());
            if (b != null && b.has("elapsed_ms")) change.addProperty("right_elapsed_ms", b.get("elapsed_ms").getAsDouble());
            changes.add(change);
         }
      }
      result.addProperty("changed_step_count", changes.size());
      result.add("changes", changes);
      return result;
   }

   private JsonObject transformPlan(JsonObject arguments, String prefix) throws Exception {
      JsonObject copy = arguments.deepCopy();
      String request = prefix + StudioJson.string(arguments, "request", "");
      copy.addProperty("request", request.trim());
      return improvePlan(copy);
   }

   private static Map<String, JsonObject> stepsById(JsonObject report) {
      Map<String, JsonObject> result = new LinkedHashMap<>();
      for (JsonElement item : StudioJson.array(report, "steps")) {
         JsonObject step = item.getAsJsonObject();
         result.put(StudioJson.string(step, "id", "step-" + result.size()), step);
      }
      return result;
   }

   JsonObject captureScreenshot(JsonObject arguments) throws Exception {
      String project = StudioJson.required(arguments, "project");
      String runId = StudioJson.required(arguments, "run_id");
      String name = StudioJson.string(arguments, "name", "dbeaver.png").replaceAll("[^A-Za-z0-9._-]", "_");
      if (!name.toLowerCase(Locale.ENGLISH).endsWith(".png")) name += ".png";
      java.nio.file.Path temporary = Files.createTempFile("dbeaver-teststudio-", ".png");
      try {
         JsonObject capture = extensions.bridge().captureScreenshot(temporary);
         if (!StudioJson.bool(capture, "supported", false)) return capture;
         JsonObject attachment = evidence.attach(project, runId, name, Files.readAllBytes(temporary));
         attachment.add("capture", capture);
         attachment.addProperty("privacy_warning", "Screenshots may contain visible database values. Review evidence before sharing.");
         return attachment;
      } finally {
         Files.deleteIfExists(temporary);
      }
   }

   JsonObject listAssertions() {
      return assertions.describe();
   }

   JsonObject listReportProviders() {
      return reports.describe();
   }

   JsonObject listAiProviders() {
      return extensions.describeAi();
   }

   JsonObject listDatabaseAdapters() {
      return extensions.describeDatabases();
   }

   JsonObject generatePlan(JsonObject arguments) throws Exception {
      String prompt = StudioJson.required(arguments, "prompt");
      if (prompt.length() > 8000) throw new IllegalArgumentException("AI plan prompt exceeds 8,000 characters");
      JsonObject context = boundedContext(StudioJson.object(arguments, "context"));
      TestStudioAiProvider provider = extensions.ai(StudioJson.string(arguments, "provider", ""));
      JsonObject generated = provider.generatePlan(prompt, context);
      JsonObject plan = generated.getAsJsonObject("plan");
      if (plan == null) throw new IllegalStateException("AI provider returned no plan");
      JsonObject validation = validator.validate(plan);
      generated.add("validation", validation);
      generated.addProperty("official_result", false);
      generated.addProperty("requires_user_acceptance", true);
      return generated;
   }

   JsonObject improvePlan(JsonObject arguments) throws Exception {
      JsonObject plan = requiredPlan(arguments);
      String request = StudioJson.required(arguments, "request");
      TestStudioAiProvider provider = extensions.ai(StudioJson.string(arguments, "provider", ""));
      JsonObject improved = provider.improvePlan(plan, request, boundedContext(StudioJson.object(arguments, "context")));
      JsonObject output = improved.getAsJsonObject("plan");
      if (output == null) throw new IllegalStateException("AI provider returned no improved plan");
      improved.add("validation", validator.validate(output));
      improved.addProperty("requires_user_acceptance", true);
      return improved;
   }

   JsonObject analyzeFailure(JsonObject arguments) throws Exception {
      JsonObject run = evidence.get(StudioJson.required(arguments, "project"), StudioJson.required(arguments, "run_id"));
      TestStudioAiProvider provider = extensions.ai(StudioJson.string(arguments, "provider", ""));
      JsonObject analysis = provider.analyzeFailure(
         StudioJson.bounded(run, 262144).getAsJsonObject(),
         boundedContext(StudioJson.object(arguments, "context"))
      );
      analysis.addProperty("official_result", false);
      analysis.addProperty("requires_review", true);
      return analysis;
   }

   private static JsonObject boundedContext(JsonObject context) {
      String json = StudioJson.GSON.toJson(context);
      if (json.length() > 262144) throw new IllegalArgumentException("AI context exceeds 256 KiB");
      return context.deepCopy();
   }

   private static JsonObject requiredPlan(JsonObject arguments) {
      JsonElement value = arguments.get("plan");
      if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("plan object is required");
      return value.getAsJsonObject();
   }

   private void requireRunner() {
      if (runs == null) throw new IllegalStateException("Test Studio runner requires the DBeaver MCP runtime");
   }

   private void requireMcp() {
      if (mcp == null) throw new IllegalStateException("Action requires the DBeaver MCP runtime");
   }
}
