package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jkiss.dbeaver.mcp.DBeaverMcpContext;
import org.jkiss.dbeaver.mcp.DBeaverMcpJobManager;
import org.jkiss.dbeaver.teststudio.model.SandboxStrategy;
import org.jkiss.dbeaver.teststudio.model.StudioTarget;
import org.jkiss.dbeaver.teststudio.spi.DatabaseAdapter;
import org.jkiss.dbeaver.teststudio.spi.StudioBridge;
import org.jkiss.dbeaver.teststudio.spi.StudioSession;

final class StudioRunService {
   private static final int MAX_SNAPSHOTS = 25;
   private final DBeaverMcpContext mcp;
   private final TestPlanStore plans;
   private final TestPlanValidator validator;
   private final VariableResolver variables;
   private final StudioExtensionRegistry extensions;
   private final AssertionRegistry assertions;
   private final RunEvidenceStore evidence;
   private final ReportRegistry reports;
   private final FixtureLoader fixtures = new FixtureLoader();
   private final ApprovalStore candidates = new ApprovalStore();
   private final ApprovalStore approvals = new ApprovalStore();

   StudioRunService(
      DBeaverMcpContext mcp,
      TestPlanStore plans,
      TestPlanValidator validator,
      VariableResolver variables,
      StudioExtensionRegistry extensions,
      AssertionRegistry assertions,
      RunEvidenceStore evidence,
      ReportRegistry reports
   ) {
      this.mcp = mcp;
      this.plans = plans;
      this.validator = validator;
      this.variables = variables;
      this.extensions = extensions;
      this.assertions = assertions;
      this.evidence = evidence;
      this.reports = reports;
   }

   JsonObject planRun(JsonObject arguments) throws Exception {
      String project = StudioJson.required(arguments, "project");
      JsonObject plan = resolvePlan(arguments, project);
      JsonObject validation = validator.validate(plan);
      if (!validation.get("valid").getAsBoolean()) {
         throw new IllegalArgumentException("Invalid test plan: " + validation.getAsJsonArray("errors"));
      }
      JsonObject definitions = StudioJson.object(plan, "variables");
      JsonObject resolvedVariables = variables.resolve(definitions);
      JsonObject resolvedPlan = StudioJson.substitute(plan, resolvedVariables).getAsJsonObject();
      boolean requiresDataWrite = requiresDataWrite(resolvedPlan);
      JsonObject fingerprintSource = new JsonObject();
      fingerprintSource.add("plan", resolvedPlan);
      fingerprintSource.add("variables", resolvedVariables);
      String fingerprint = StudioJson.fingerprint(fingerprintSource);
      JsonObject candidate = candidates.create(project, fingerprint, resolvedPlan, resolvedVariables, requiresDataWrite);
      candidate.addProperty("candidate_id", candidate.remove("approval_id").getAsString());
      candidate.addProperty("plan_id", StudioJson.required(resolvedPlan, "id"));
      candidate.addProperty("plan_name", StudioJson.required(resolvedPlan, "name"));
      candidate.add("variables", variables.masked(resolvedVariables, definitions));
      candidate.add("preview", preview(resolvedPlan));
      candidate.addProperty("requires_native_confirmation", requiresDataWrite || StudioJson.bool(StudioJson.object(resolvedPlan, "policy"), "confirm_read_only", false));
      return candidate;
   }

   JsonObject approveRun(JsonObject arguments) throws Exception {
      String candidateId = StudioJson.required(arguments, "candidate_id");
      String fingerprint = StudioJson.required(arguments, "fingerprint");
      if (!StudioJson.bool(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      ApprovalStore.Approval candidate = candidates.peek(candidateId);
      if (!candidate.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Candidate fingerprint mismatch");
      JsonObject plan = candidate.plan();
      JsonObject preview = preview(plan);
      String message = "Plan: " + StudioJson.string(plan, "name", StudioJson.string(plan, "id", "Test Studio plan"))
         + "\nProject: " + candidate.project()
         + "\nSteps: " + preview.get("total_steps").getAsInt()
         + "\nMutation steps: " + preview.get("mutation_steps").getAsInt()
         + "\nSandbox: " + preview.get("requested_sandbox").getAsString()
         + "\nFingerprint: " + fingerprint.substring(0, Math.min(16, fingerprint.length()))
         + "…\n\nThe approval is one-time and bound to the resolved plan, targets, variables, and step list."
         + (candidate.requiresDataWrite() ? "\n\nDatabase writes may occur. Cleanup and rollback cannot undo external calls, sequences, messages, files, jobs, or autonomous transactions." : "");
      if (!extensions.bridge().confirm("Approve AI Database Test Studio run?", message)) {
         JsonObject cancelled = new JsonObject();
         cancelled.addProperty("approved", false);
         cancelled.addProperty("cancelled", true);
         return cancelled;
      }
      candidate = candidates.consume(candidateId, fingerprint);
      JsonObject approved = approvals.create(
         candidate.project(),
         candidate.fingerprint(),
         candidate.plan(),
         candidate.variables(),
         candidate.requiresDataWrite()
      );
      approved.addProperty("approved", true);
      approved.addProperty("candidate_id", candidateId);
      return approved;
   }

   boolean approvalRequiresDataWrite(String approvalId) {
      return approvals.peek(approvalId).requiresDataWrite();
   }

   JsonObject cancelApproval(JsonObject arguments) {
      String id = StudioJson.required(arguments, "approval_id");
      JsonObject first = candidates.cancel(id);
      if (!first.get("cancelled").getAsBoolean()) return approvals.cancel(id);
      return first;
   }

   JsonObject runPlan(JsonObject arguments) {
      String approvalId = StudioJson.required(arguments, "approval_id");
      String fingerprint = StudioJson.required(arguments, "fingerprint");
      ApprovalStore.Approval approval = approvals.consume(approvalId, fingerprint);
      String runId = "run-" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14) + "-" + UUID.randomUUID().toString().substring(0, 8);
      String jobId = mcp.jobs().submit("test-studio", "plan-run", true, context -> execute(approval, runId, context));
      JsonObject result = new JsonObject();
      result.addProperty("submitted", true);
      result.addProperty("job_id", jobId);
      result.addProperty("run_id", runId);
      result.addProperty("project", approval.project());
      result.addProperty("plan_id", StudioJson.string(approval.plan(), "id", ""));
      result.addProperty("fingerprint", approval.fingerprint());
      return result;
   }

   JsonObject retryFailed(JsonObject arguments) throws Exception {
      String project = StudioJson.required(arguments, "project");
      String runId = StudioJson.required(arguments, "run_id");
      JsonObject previous = evidence.get(project, runId);
      JsonObject report = previous.getAsJsonObject("report");
      if (report == null) throw new IllegalArgumentException("Run has no canonical report: " + runId);
      JsonObject plan = evidence.getPlan(project, runId);
      JsonArray failedIds = new JsonArray();
      for (JsonElement item : StudioJson.array(report, "steps")) {
         JsonObject step = item.getAsJsonObject();
         if (!StudioJson.bool(step, "passed", false)) failedIds.add(StudioJson.string(step, "id", ""));
      }
      if (failedIds.isEmpty()) throw new IllegalArgumentException("Run has no failed steps");
      JsonArray selected = new JsonArray();
      Set<String> ids = new HashSet<>();
      failedIds.forEach(item -> ids.add(item.getAsString()));
      for (JsonElement item : StudioJson.array(plan, "steps")) {
         JsonObject step = item.getAsJsonObject();
         if (ids.contains(StudioJson.string(step, "id", ""))) {
            if (!isRetrySafe(step)) throw new IllegalArgumentException("Failed non-idempotent step cannot be retried automatically: " + StudioJson.string(step, "id", ""));
            selected.add(step.deepCopy());
         }
      }
      plan.add("steps", selected);
      plan.addProperty("id", StudioJson.string(plan, "id", "plan") + "-retry");
      plan.addProperty("name", StudioJson.string(plan, "name", "Plan") + " — retry failed steps");
      JsonObject request = new JsonObject();
      request.addProperty("project", project);
      request.add("plan", plan);
      JsonObject result = planRun(request);
      result.addProperty("source_run_id", runId);
      return result;
   }

   private JsonObject execute(
      ApprovalStore.Approval approval,
      String runId,
      DBeaverMcpJobManager.JobContext jobContext
   ) throws Exception {
      String project = approval.project();
      JsonObject plan = approval.plan().deepCopy();
      JsonObject definitions = StudioJson.object(plan, "variables");
      JsonObject maskedVariables = variables.masked(approval.variables(), definitions);
      evidence.start(project, runId, plan, maskedVariables);
      RunContext run = new RunContext(project, runId, plan, approval.variables().deepCopy(), jobContext);
      JsonObject canonical = new JsonObject();
      canonical.addProperty("run_id", runId);
      canonical.addProperty("plan_id", StudioJson.string(plan, "id", ""));
      canonical.addProperty("name", StudioJson.string(plan, "name", "Test Studio run"));
      canonical.addProperty("fingerprint", approval.fingerprint());
      canonical.addProperty("started_at", StudioJson.now());
      canonical.add("variables", maskedVariables);
      canonical.add("targets", new JsonObject());
      canonical.add("setup", new JsonArray());
      canonical.add("steps", new JsonArray());
      JsonObject cleanupResult = new JsonObject();
      cleanupResult.addProperty("attempted", true);
      cleanupResult.addProperty("passed", true);
      cleanupResult.add("steps", new JsonArray());
      Throwable infrastructureFailure = null;
      boolean passed = false;
      boolean cancelled = false;

      try {
         SectionResult setup = executeSection(run, "setup", StudioJson.array(plan, "setup"), false);
         canonical.add("setup", setup.reports());
         if (setup.passed()) {
            SectionResult main = executeSection(run, "steps", StudioJson.array(plan, "steps"), false);
            canonical.add("steps", main.reports());
            passed = main.passed();
         }
      } catch (InterruptedException e) {
         cancelled = true;
         infrastructureFailure = e;
         // Clear Future.cancel(true)'s interrupt flag so cleanup and transaction finalization can run.
         Thread.interrupted();
      } catch (Throwable e) {
         infrastructureFailure = e;
      } finally {
         try {
            SectionResult cleanup = executeSection(run, "cleanup", StudioJson.array(plan, "cleanup"), true);
            cleanupResult.addProperty("passed", cleanup.passed());
            cleanupResult.add("steps", cleanup.reports());
            if (!cleanup.passed()) passed = false;
         } catch (Throwable cleanupFailure) {
            cleanupResult.addProperty("passed", false);
            cleanupResult.addProperty("error", StudioJson.safe(cleanupFailure));
            passed = false;
            if (infrastructureFailure != null) infrastructureFailure.addSuppressed(cleanupFailure);
         }
         JsonObject transactionCleanup = closeSessions(run, passed && infrastructureFailure == null && !cancelled);
         cleanupResult.add("sessions", transactionCleanup);
         if (!StudioJson.bool(transactionCleanup, "passed", false)) passed = false;
      }

      String state;
      if (cancelled) state = "cancelled";
      else if (infrastructureFailure != null) state = "failed";
      else if (!StudioJson.bool(cleanupResult, "passed", false)) state = "cleanup_failed";
      else state = passed ? "succeeded" : "failed";
      canonical.addProperty("state", state);
      canonical.addProperty("passed", state.equals("succeeded"));
      canonical.addProperty("finished_at", StudioJson.now());
      canonical.add("cleanup", cleanupResult);
      canonical.add("targets", run.targetSummaries);
      if (infrastructureFailure != null && !(infrastructureFailure instanceof InterruptedException)) {
         canonical.addProperty("error", StudioJson.safe(infrastructureFailure));
         canonical.addProperty("error_type", infrastructureFailure.getClass().getSimpleName());
      }
      JsonObject manifest = evidence.finish(project, runId, canonical, state, cleanupResult);
      JsonArray generatedReports = new JsonArray();
      for (JsonElement format : reportFormats(plan)) {
         try {
            generatedReports.add(reports.generate(evidence, project, runId, format.getAsString(), new JsonObject(), true));
         } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("format", format.getAsString());
            error.addProperty("error", StudioJson.safe(e));
            generatedReports.add(error);
         }
      }
      JsonObject result = new JsonObject();
      result.add("manifest", manifest);
      result.add("result", canonical);
      result.add("reports", generatedReports);
      if (cancelled) throw new InterruptedException("Test Studio run cancelled after cleanup");
      return result;
   }

   private SectionResult executeSection(RunContext run, String section, JsonArray steps, boolean cleanup) throws Exception {
      JsonArray reports = new JsonArray();
      boolean passed = true;
      for (JsonElement item : steps) {
         if (!cleanup) run.job.checkCancelled();
         JsonObject step = item.getAsJsonObject();
         JsonObject report = executeStep(run, step, section, cleanup);
         reports.add(report);
         run.evidenceIndex.incrementAndGet();
         evidence.step(run.project, run.runId, run.evidenceIndex.get(), StudioJson.string(step, "id", section), report);
         if (!StudioJson.bool(report, "passed", false)) {
            passed = false;
            if (!cleanup && !StudioJson.bool(step, "continue_on_failure", false)) break;
         }
      }
      return new SectionResult(passed, reports);
   }

   private JsonObject executeStep(RunContext run, JsonObject step, String section, boolean cleanup) throws Exception {
      String id = StudioJson.string(step, "id", section + "-" + (run.evidenceIndex.get() + 1));
      String type = StudioJson.required(step, "type");
      int attempts = StudioJson.integer(step, "attempts", 1, 1, 6);
      int delayMs = StudioJson.integer(step, "retry_delay_ms", 0, 0, 10000);
      if (attempts > 1 && !isRetrySafe(step)) {
         throw new IllegalArgumentException("Non-idempotent step cannot be retried automatically: " + id);
      }
      JsonArray attemptReports = new JsonArray();
      JsonObject finalReport = null;
      for (int attempt = 1; attempt <= attempts; attempt++) {
         if (!cleanup) run.job.checkCancelled();
         long started = System.nanoTime();
         JsonObject attemptReport = new JsonObject();
         attemptReport.addProperty("attempt", attempt);
         try {
            JsonObject operation = executeOperation(run, step, cleanup);
            JsonObject assertionResult;
            boolean assertionPassed;
            if (type.equals("wait_until")) {
               assertionPassed = StudioJson.bool(operation, "passed", false);
               assertionResult = operation.has("assertions") && operation.get("assertions").isJsonObject()
                  ? operation.getAsJsonObject("assertions").deepCopy()
                  : emptyAssertionSummary(assertionPassed);
            } else {
               JsonElement actual = operation.has("result") ? operation.get("result") : operation;
               assertionResult = assertions.evaluate(actual, StudioJson.array(step, "assertions"), operation, run.variables, operationSession(run, step));
               assertionPassed = assertionResult.get("passed").getAsBoolean();
            }
            attemptReport.addProperty("passed", assertionPassed);
            attemptReport.add("assertions", assertionResult);
            attemptReport.add("operation", StudioJson.bounded(operation, 65536));
            finalReport = report(id, type, section, assertionPassed, started, attemptReports, attemptReport);
            if (assertionPassed) break;
         } catch (InterruptedException e) {
            throw e;
         } catch (Exception e) {
            attemptReport.addProperty("passed", false);
            attemptReport.addProperty("error", StudioJson.safe(e));
            attemptReport.addProperty("error_type", e.getClass().getSimpleName());
            finalReport = report(id, type, section, false, started, attemptReports, attemptReport);
         }
         attemptReports.add(attemptReport);
         if (attempt < attempts && delayMs > 0) {
            if (cleanup) Thread.sleep(delayMs);
            else sleep(run.job, delayMs);
         }
      }
      if (finalReport == null) throw new IllegalStateException("Step produced no report: " + id);
      run.stepResults.add(id, finalReport.has("result") ? finalReport.get("result") : finalReport.deepCopy());
      String saveAs = StudioJson.string(step, "save_as", "");
      if (!saveAs.isBlank()) run.variables.add(saveAs, StudioJson.bounded(run.stepResults.get(id), 65536));
      return finalReport;
   }

   private static JsonObject emptyAssertionSummary(boolean passed) {
      JsonObject result = new JsonObject();
      result.addProperty("passed", passed);
      result.addProperty("count", 0);
      result.addProperty("passed_count", 0);
      result.addProperty("failed_count", passed ? 0 : 1);
      result.addProperty("unsupported_count", 0);
      result.add("assertions", new JsonArray());
      return result;
   }

   private static JsonObject report(
      String id,
      String type,
      String section,
      boolean passed,
      long started,
      JsonArray previousAttempts,
      JsonObject finalAttempt
   ) {
      JsonArray allAttempts = previousAttempts.deepCopy();
      allAttempts.add(finalAttempt.deepCopy());
      JsonObject result = new JsonObject();
      result.addProperty("id", id);
      result.addProperty("type", type);
      result.addProperty("section", section);
      result.addProperty("passed", passed);
      result.addProperty("status", passed ? "passed" : finalAttempt.has("error") ? "error" : "failed");
      result.addProperty("attempt_count", allAttempts.size());
      result.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000.0);
      result.add("attempts", allAttempts);
      if (finalAttempt.has("operation")) result.add("result", finalAttempt.get("operation").deepCopy());
      if (finalAttempt.has("assertions")) result.add("assertions", finalAttempt.get("assertions").deepCopy());
      if (finalAttempt.has("error")) result.add("error", finalAttempt.get("error").deepCopy());
      return result;
   }

   private JsonObject executeOperation(RunContext run, JsonObject step, boolean cleanup) throws Exception {
      String type = StudioJson.required(step, "type");
      return switch (type) {
         case "query" -> executeQuery(run, step);
         case "sql" -> executeSql(run, step, cleanup);
         case "call_tool" -> executeTool(step);
         case "insert_fixture", "import_fixture" -> executeFixture(run, step);
         case "wait_until" -> executeWait(run, step, cleanup);
         case "assert" -> executeAssertSource(run, step);
         case "snapshot" -> executeSnapshot(run, step);
         case "compare_snapshot" -> executeSnapshotComparison(run, step);
         case "schema_contract" -> executeFacade("dbeaver_quality", "schema_contract", StudioJson.object(step, "arguments"));
         case "migration_rehearsal" -> executeFacade("dbeaver_test", "migration_rehearsal", StudioJson.object(step, "arguments"));
         case "group" -> executeNested(run, step, false);
         case "parallel_read" -> executeNested(run, step, true);
         default -> throw new IllegalArgumentException("Unsupported step type: " + type);
      };
   }

   private JsonObject executeQuery(RunContext run, JsonObject step) throws Exception {
      String sql = StudioJson.required(step, "sql");
      if (!StudioSqlSafety.isReadOnly(sql)) throw new IllegalArgumentException("query step accepts read-only SQL only");
      TargetSession target = target(run, step);
      return target.session.execute(sql, StudioJson.integer(step, "max_rows", 200, 1, 1000), StudioJson.integer(step, "timeout_seconds", 30, 1, 300));
   }

   private JsonObject executeSql(RunContext run, JsonObject step, boolean cleanup) throws Exception {
      String sql = StudioJson.required(step, "sql");
      TargetSession target = target(run, step);
      if (target.selected == SandboxStrategy.READ_ONLY && !StudioSqlSafety.isReadOnly(sql)) {
         throw new IllegalArgumentException("Mutation SQL is blocked by the read_only sandbox");
      }
      if (!cleanup && !StudioSqlSafety.isReadOnly(sql) && !StudioJson.bool(step, "allow_mutation", true)) {
         throw new IllegalArgumentException("Mutation SQL requires allow_mutation=true");
      }
      return target.session.execute(sql, StudioJson.integer(step, "max_rows", 200, 1, 1000), StudioJson.integer(step, "timeout_seconds", 30, 1, 300));
   }

   private JsonObject executeTool(JsonObject step) throws Exception {
      String tool = StudioJson.required(step, "tool");
      if (Set.of("dbeaver_teststudio", "dbeaver_test", "dbeaver_job").contains(tool)) {
         throw new IllegalArgumentException("Orchestration recursion is not allowed from a Test Studio call_tool step: " + tool);
      }
      JsonObject descriptor = mcp.tools().describe(tool);
      JsonObject annotations = descriptor.getAsJsonObject("annotations");
      boolean readOnly = annotations != null && StudioJson.bool(annotations, "readOnlyHint", false);
      if (!readOnly && !StudioJson.bool(step, "allow_non_read_only", false)) {
         throw new IllegalArgumentException("Non-read-only tool requires allow_non_read_only=true: " + tool);
      }
      return mcp.tools().invoke(tool, StudioJson.object(step, "arguments"));
   }

   private JsonObject executeFixture(RunContext run, JsonObject step) throws Exception {
      TargetSession target = target(run, step);
      if (target.selected == SandboxStrategy.READ_ONLY) throw new IllegalArgumentException("Fixtures are blocked by the read_only sandbox");
      JsonArray rows = fixtures.load(run.project, step);
      String table = StudioJson.required(step, "table");
      if (rows.size() > 10000) throw new IllegalArgumentException("Fixture exceeds 10,000 rows");
      int affected = 0;
      JsonArray previews = new JsonArray();
      for (int index = 0; index < rows.size(); index++) {
         run.job.checkCancelled();
         JsonElement item = rows.get(index);
         if (!item.isJsonObject()) throw new IllegalArgumentException("Fixture row " + index + " must be an object");
         JsonObject row = item.getAsJsonObject();
         List<String> columns = new ArrayList<>(row.keySet());
         List<JsonElement> values = columns.stream().map(row::get).toList();
         String sql = target.adapter.insertSql(table, columns, values);
         JsonObject result = target.session.execute(sql, 1, StudioJson.integer(step, "timeout_seconds", 30, 1, 300));
         affected += result.has("update_count") ? result.get("update_count").getAsInt() : 0;
         if (previews.size() < 20) previews.add(result);
      }
      JsonObject result = new JsonObject();
      result.addProperty("fixture_rows", rows.size());
      result.addProperty("affected_rows", affected);
      result.addProperty("preview_count", previews.size());
      result.add("previews", previews);
      return result;
   }

   private JsonObject executeWait(RunContext run, JsonObject step, boolean cleanup) throws Exception {
      JsonObject condition = StudioJson.object(step, "condition");
      if (condition.isEmpty()) throw new IllegalArgumentException("wait_until requires condition step");
      if (!isRetrySafe(condition)) throw new IllegalArgumentException("wait_until condition must be read-only or idempotent");
      int attempts = StudioJson.integer(step, "max_attempts", 20, 1, 60);
      int delayMs = StudioJson.integer(step, "delay_ms", 1000, 0, 10000);
      JsonArray observations = new JsonArray();
      JsonObject last = null;
      for (int attempt = 1; attempt <= attempts; attempt++) {
         if (!cleanup) run.job.checkCancelled();
         JsonObject operation = executeOperation(run, condition, cleanup);
         JsonObject assertion = assertions.evaluate(operation, StudioJson.array(step, "assertions"), operation, run.variables, operationSession(run, condition));
         JsonObject observation = new JsonObject();
         observation.addProperty("attempt", attempt);
         observation.addProperty("passed", assertion.get("passed").getAsBoolean());
         observation.add("assertions", assertion);
         observation.add("result", StudioJson.bounded(operation, 16384));
         observations.add(observation);
         last = observation;
         if (assertion.get("passed").getAsBoolean()) break;
         if (attempt < attempts) {
            if (cleanup) Thread.sleep(delayMs);
            else sleep(run.job, delayMs);
         }
      }
      JsonObject result = new JsonObject();
      boolean passed = last != null && StudioJson.bool(last, "passed", false);
      result.addProperty("passed", passed);
      result.addProperty("attempt_count", observations.size());
      result.add("observations", observations);
      result.add("assertions", last != null && last.has("assertions")
         ? last.get("assertions").deepCopy()
         : emptyAssertionSummary(passed));
      return result;
   }

   private JsonObject executeAssertSource(RunContext run, JsonObject step) {
      JsonElement value = sourceValue(run, step);
      JsonObject result = new JsonObject();
      result.add("value", StudioJson.bounded(value == null ? JsonNull.INSTANCE : value, 65536));
      return result;
   }

   private JsonObject executeSnapshot(RunContext run, JsonObject step) {
      if (run.snapshots.size() >= MAX_SNAPSHOTS) throw new IllegalStateException("A run may contain at most " + MAX_SNAPSHOTS + " snapshots");
      String name = StudioJson.required(step, "name");
      JsonElement value = sourceValue(run, step);
      JsonElement bounded = StudioJson.bounded(value == null ? JsonNull.INSTANCE : value, 1024 * 1024);
      run.snapshots.put(name, bounded);
      JsonObject result = new JsonObject();
      result.addProperty("name", name);
      result.addProperty("fingerprint", StudioJson.fingerprint(bounded));
      result.add("value", bounded.deepCopy());
      return result;
   }

   private JsonObject executeSnapshotComparison(RunContext run, JsonObject step) {
      String leftName = StudioJson.required(step, "left");
      String rightName = StudioJson.required(step, "right");
      JsonElement left = run.snapshots.get(leftName), right = run.snapshots.get(rightName);
      if (left == null || right == null) throw new IllegalArgumentException("Unknown run snapshot: " + (left == null ? leftName : rightName));
      JsonObject result = new JsonObject();
      result.addProperty("left", leftName);
      result.addProperty("right", rightName);
      result.addProperty("equal", left.equals(right));
      result.addProperty("left_fingerprint", StudioJson.fingerprint(left));
      result.addProperty("right_fingerprint", StudioJson.fingerprint(right));
      return result;
   }

   private JsonObject executeNested(RunContext run, JsonObject step, boolean parallelRequested) throws Exception {
      JsonArray nested = StudioJson.array(step, "steps");
      JsonArray reports = new JsonArray();
      boolean passed = true;
      for (JsonElement item : nested) {
         JsonObject nestedStep = item.getAsJsonObject();
         if (parallelRequested && !isRetrySafe(nestedStep)) {
            throw new IllegalArgumentException("parallel_read accepts read-only/idempotent nested steps only");
         }
         JsonObject report = executeStep(run, nestedStep, "nested", false);
         reports.add(report);
         if (!StudioJson.bool(report, "passed", false)) passed = false;
      }
      JsonObject result = new JsonObject();
      result.addProperty("passed", passed);
      result.addProperty("execution_mode", parallelRequested ? "bounded_sequential_fallback" : "sequential");
      result.add("steps", reports);
      return result;
   }

   private JsonObject executeFacade(String tool, String action, JsonObject arguments) throws Exception {
      JsonObject payload = new JsonObject();
      payload.addProperty("action", action);
      payload.add("arguments", arguments);
      return mcp.tools().invoke(tool, payload);
   }

   private JsonElement sourceValue(RunContext run, JsonObject step) {
      if (step.has("value")) return step.get("value").deepCopy();
      String sourceStep = StudioJson.string(step, "source_step", "");
      JsonElement value = sourceStep.isBlank() ? run.stepResults : run.stepResults.get(sourceStep);
      String path = StudioJson.string(step, "path", "");
      return path.isBlank() ? value : StudioJson.pointer(value, path);
   }

   private StudioSession operationSession(RunContext run, JsonObject step) {
      String target = StudioJson.string(step, "target", "");
      TargetSession session = target.isBlank() ? null : run.sessions.get(target);
      return session == null ? null : session.session;
   }

   private TargetSession target(RunContext run, JsonObject step) throws Exception {
      String alias = StudioJson.string(step, "target", "default");
      TargetSession existing = run.sessions.get(alias);
      if (existing != null) return existing;
      JsonObject config = StudioJson.object(run.plan.getAsJsonObject("targets"), alias);
      if (config.isEmpty()) throw new IllegalArgumentException("Unknown target alias: " + alias);
      StudioTarget target = new StudioTarget(
         alias,
         StudioJson.required(config, "connection"),
         StudioJson.string(config, "project", run.project),
         StudioJson.bool(config, "auto_connect", true)
      );
      SandboxStrategy requested = SandboxStrategy.parse(
         StudioJson.string(config, "sandbox", StudioJson.string(StudioJson.object(run.plan, "policy"), "sandbox", "transaction"))
      );
      SandboxStrategy selected = selectSandbox(requested, extensions.bridge().capabilities());
      StudioSession session;
      try {
         session = extensions.bridge().openSession(target, selected);
      } catch (Exception primary) {
         if (selected == SandboxStrategy.TRANSACTION && StudioJson.bool(StudioJson.object(run.plan, "policy"), "allow_sandbox_fallback", true)) {
            selected = SandboxStrategy.EXPLICIT_CLEANUP;
            session = extensions.bridge().openSession(target, selected);
         } else throw primary;
      }
      DatabaseAdapter adapter = extensions.database(session.productName(), session.driverId());
      TargetSession created = new TargetSession(target, requested, selected, session, adapter);
      run.sessions.put(alias, created);
      JsonObject summary = session.connection();
      summary.addProperty("requested_sandbox", requested.name().toLowerCase(Locale.ENGLISH));
      summary.addProperty("selected_sandbox", selected.name().toLowerCase(Locale.ENGLISH));
      summary.addProperty("database_adapter", adapter.id());
      summary.add("database_capabilities", adapter.capabilities());
      run.targetSummaries.add(alias, summary);
      return created;
   }

   private JsonObject closeSessions(RunContext run, boolean success) {
      JsonArray items = new JsonArray();
      boolean passed = true;
      boolean commitOnSuccess = StudioJson.bool(StudioJson.object(run.plan, "policy"), "commit_on_success", false);
      for (TargetSession target : run.sessions.values()) {
         JsonObject item = new JsonObject();
         item.addProperty("target", target.target.alias());
         item.addProperty("selected_sandbox", target.selected.name().toLowerCase(Locale.ENGLISH));
         try {
            if (target.selected == SandboxStrategy.TRANSACTION || target.selected == SandboxStrategy.SAVEPOINT) {
               if (success && commitOnSuccess) {
                  target.session.commit();
                  item.addProperty("committed", true);
               } else {
                  target.session.rollback();
                  item.addProperty("rolled_back", true);
               }
            }
         } catch (Exception e) {
            passed = false;
            item.addProperty("transaction_error", StudioJson.safe(e));
         }
         try {
            target.session.close();
            item.addProperty("closed", true);
         } catch (Exception e) {
            passed = false;
            item.addProperty("close_error", StudioJson.safe(e));
         }
         items.add(item);
      }
      JsonObject result = new JsonObject();
      result.addProperty("passed", passed);
      result.addProperty("count", items.size());
      result.add("targets", items);
      return result;
   }

   private JsonObject resolvePlan(JsonObject arguments, String project) throws Exception {
      if (arguments.has("plan") && arguments.get("plan").isJsonObject()) return arguments.getAsJsonObject("plan").deepCopy();
      return plans.get(project, StudioJson.required(arguments, "plan_id")).getAsJsonObject("plan");
   }

   private boolean requiresDataWrite(JsonObject plan) throws Exception {
      return sectionRequiresWrite(StudioJson.array(plan, "setup"))
         || sectionRequiresWrite(StudioJson.array(plan, "steps"))
         || sectionRequiresWrite(StudioJson.array(plan, "cleanup"));
   }

   private boolean sectionRequiresWrite(JsonArray steps) throws Exception {
      for (JsonElement item : steps) {
         JsonObject step = item.getAsJsonObject();
         String type = StudioJson.string(step, "type", "");
         if (Set.of("sql", "insert_fixture", "import_fixture", "migration_rehearsal").contains(type)) {
            if (!type.equals("sql") || !StudioSqlSafety.isReadOnly(StudioJson.string(step, "sql", ""))) return true;
         }
         if (type.equals("call_tool")) {
            JsonObject descriptor = mcp.tools().describe(StudioJson.required(step, "tool"));
            JsonObject annotations = descriptor.getAsJsonObject("annotations");
            if (annotations == null || !StudioJson.bool(annotations, "readOnlyHint", false)) return true;
         }
         if (type.equals("group") || type.equals("parallel_read")) {
            if (sectionRequiresWrite(StudioJson.array(step, "steps"))) return true;
         }
         if (type.equals("wait_until")) {
            JsonObject condition = StudioJson.object(step, "condition");
            JsonArray nested = new JsonArray(); nested.add(condition);
            if (sectionRequiresWrite(nested)) return true;
         }
      }
      return false;
   }

   private JsonObject preview(JsonObject plan) throws Exception {
      JsonObject result = new JsonObject();
      int setup = StudioJson.array(plan, "setup").size();
      int steps = StudioJson.array(plan, "steps").size();
      int cleanup = StudioJson.array(plan, "cleanup").size();
      result.addProperty("setup_steps", setup);
      result.addProperty("test_steps", steps);
      result.addProperty("cleanup_steps", cleanup);
      result.addProperty("total_steps", setup + steps + cleanup);
      int mutations = countMutations(StudioJson.array(plan, "setup")) + countMutations(StudioJson.array(plan, "steps")) + countMutations(StudioJson.array(plan, "cleanup"));
      result.addProperty("mutation_steps", mutations);
      result.addProperty("requested_sandbox", StudioJson.string(StudioJson.object(plan, "policy"), "sandbox", "transaction"));
      result.addProperty("commit_on_success", StudioJson.bool(StudioJson.object(plan, "policy"), "commit_on_success", false));
      result.addProperty("external_side_effects_rollbackable", false);
      result.add("targets", plan.getAsJsonObject("targets").deepCopy());
      return result;
   }

   private int countMutations(JsonArray steps) throws Exception {
      int count = 0;
      for (JsonElement item : steps) {
         JsonObject step = item.getAsJsonObject();
         String type = StudioJson.string(step, "type", "");
         if (type.equals("sql") && !StudioSqlSafety.isReadOnly(StudioJson.string(step, "sql", ""))) count++;
         else if (Set.of("insert_fixture", "import_fixture", "migration_rehearsal").contains(type)) count++;
         else if (type.equals("call_tool")) {
            JsonObject annotations = mcp.tools().describe(StudioJson.required(step, "tool")).getAsJsonObject("annotations");
            if (annotations == null || !StudioJson.bool(annotations, "readOnlyHint", false)) count++;
         } else if (type.equals("group") || type.equals("parallel_read")) count += countMutations(StudioJson.array(step, "steps"));
      }
      return count;
   }

   private static boolean isRetrySafe(JsonObject step) {
      if (StudioJson.bool(step, "idempotent", false)) return true;
      String type = StudioJson.string(step, "type", "");
      if (Set.of("query", "assert", "snapshot", "compare_snapshot", "schema_contract", "wait_until", "parallel_read").contains(type)) return true;
      return type.equals("sql") && StudioSqlSafety.isReadOnly(StudioJson.string(step, "sql", ""));
   }

   private static SandboxStrategy selectSandbox(SandboxStrategy requested, JsonObject bridgeCapabilities) {
      if (requested == SandboxStrategy.SAVEPOINT && !StudioJson.bool(bridgeCapabilities, "savepoints", false)) return SandboxStrategy.TRANSACTION;
      if (requested == SandboxStrategy.TEMP_SCHEMA) return SandboxStrategy.EXPLICIT_CLEANUP;
      return requested;
   }

   private static void sleep(DBeaverMcpJobManager.JobContext context, int delayMs) throws InterruptedException {
      int remaining = delayMs;
      while (remaining > 0) {
         context.checkCancelled();
         int step = Math.min(remaining, 250);
         Thread.sleep(step);
         remaining -= step;
      }
   }

   private static JsonArray reportFormats(JsonObject plan) {
      JsonArray formats = StudioJson.array(StudioJson.object(plan, "evidence"), "report_formats");
      if (!formats.isEmpty()) return formats;
      JsonArray defaults = new JsonArray();
      defaults.add("json");
      defaults.add("junit");
      defaults.add("html");
      defaults.add("markdown");
      return defaults;
   }

   private record SectionResult(boolean passed, JsonArray reports) {
   }

   private record TargetSession(
      StudioTarget target,
      SandboxStrategy requested,
      SandboxStrategy selected,
      StudioSession session,
      DatabaseAdapter adapter
   ) {
   }

   private static final class RunContext {
      final String project;
      final String runId;
      final JsonObject plan;
      final JsonObject variables;
      final DBeaverMcpJobManager.JobContext job;
      final Map<String, TargetSession> sessions = new LinkedHashMap<>();
      final JsonObject targetSummaries = new JsonObject();
      final JsonObject stepResults = new JsonObject();
      final Map<String, JsonElement> snapshots = new LinkedHashMap<>();
      final AtomicInteger evidenceIndex = new AtomicInteger();

      RunContext(String project, String runId, JsonObject plan, JsonObject variables, DBeaverMcpJobManager.JobContext job) {
         this.project = project;
         this.runId = runId;
         this.plan = plan;
         this.variables = variables;
         this.job = job;
      }
   }
}
