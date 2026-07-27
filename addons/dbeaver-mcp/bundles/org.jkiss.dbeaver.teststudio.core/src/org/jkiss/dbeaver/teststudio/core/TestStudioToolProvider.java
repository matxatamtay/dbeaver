package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.util.*;
import org.jkiss.dbeaver.mcp.*;
import org.jkiss.dbeaver.teststudio.api.TestStudioApi;

public final class TestStudioToolProvider implements DBeaverMcpToolProvider {
   private static final List<String> ACTIONS = List.of(
      "discover",
      "capabilities",
      "validate_plan",
      "migrate_plan",
      "create_plan",
      "update_plan",
      "save_plan",
      "import_plan",
      "export_plan",
      "list_plans",
      "get_plan",
      "clone_plan",
      "delete_plan",
      "explain_plan",
      "plan_run",
      "approve_run",
      "cancel_approval",
      "run_plan",
      "run_step",
      "retry_failed",
      "cleanup_run",
      "run_status",
      "run_result",
      "cancel_run",
      "list_runs",
      "get_run",
      "delete_run",
      "pin_run",
      "cleanup_runs",
      "capture_screenshot",
      "list_assertions",
      "list_report_providers",
      "generate_report",
      "list_reports",
      "get_report",
      "delete_report",
      "list_ai_providers",
      "generate_plan",
      "improve_plan",
      "generate_assertions",
      "suggest_fixtures",
      "suggest_cleanup",
      "analyze_failure",
      "compare_runs",
      "list_database_adapters"
   );

   private DBeaverMcpContext context;
   private TestStudioRuntime runtime;

   @Override
   public String id() {
      return "ai-database-test-studio";
   }

   @Override
   public int priority() {
      return 200;
   }

   @Override
   public void registerTools(DBeaverMcpToolRegistrar registrar, DBeaverMcpContext context) {
      this.context = context;
      this.runtime = new TestStudioRuntime(context);
      TestStudioApi.install(runtime);
      registrar.register(new DBeaverMcpToolDefinition(
         "dbeaver_teststudio",
         "Create, persist, approve, run, inspect, report, and AI-assist deterministic database test plans with always-run cleanup and evidence.",
         schema(),
         Set.of(),
         false,
         false,
         false,
         this::execute
      ));
   }

   private JsonObject execute(JsonObject request) throws Exception {
      String action = required(request, "action");
      if (action.equals("discover")) return discovery();
      JsonObject arguments = object(request, "arguments");
      require(DBeaverMcpScope.TEST);
      enforceScopes(action, arguments);
      return switch (action) {
         case "capabilities" -> runtime.capabilities();
         case "validate_plan" -> runtime.validatePlan(arguments);
         case "migrate_plan" -> runtime.migratePlan(arguments);
         case "create_plan", "save_plan", "import_plan" -> runtime.savePlan(
            required(arguments, "project"),
            requiredObject(arguments, "plan"),
            bool(arguments, "overwrite", false)
         );
         case "update_plan" -> runtime.savePlan(required(arguments, "project"), requiredObject(arguments, "plan"), true);
         case "export_plan", "get_plan" -> runtime.getPlan(required(arguments, "project"), required(arguments, "plan_id"));
         case "list_plans" -> runtime.listPlans(required(arguments, "project"));
         case "clone_plan" -> runtime.clonePlan(arguments);
         case "delete_plan" -> runtime.deletePlan(arguments);
         case "explain_plan" -> runtime.explainPlan(arguments);
         case "plan_run" -> runtime.planRun(arguments);
         case "approve_run" -> runtime.approveRun(arguments);
         case "cancel_approval" -> runtime.cancelApproval(arguments);
         case "run_plan" -> runtime.runPlan(arguments);
         case "run_step" -> runtime.runStep(arguments);
         case "retry_failed" -> runtime.retryFailed(arguments);
         case "cleanup_run" -> runtime.cleanupRun(arguments);
         case "run_status" -> runtime.runStatus(arguments, false);
         case "run_result" -> runtime.runStatus(arguments, true);
         case "cancel_run" -> runtime.cancelRun(arguments);
         case "list_runs" -> runtime.listRuns(required(arguments, "project"), integer(arguments, "limit", 100, 1, 500));
         case "get_run" -> runtime.getRun(required(arguments, "project"), required(arguments, "run_id"));
         case "delete_run" -> runtime.deleteRun(arguments);
         case "pin_run" -> runtime.pinRun(arguments);
         case "cleanup_runs" -> runtime.cleanupRuns(arguments);
         case "capture_screenshot" -> runtime.captureScreenshot(arguments);
         case "list_assertions" -> runtime.listAssertions();
         case "list_report_providers" -> runtime.listReportProviders();
         case "generate_report" -> runtime.generateReport(arguments);
         case "list_reports" -> runtime.listReports(arguments);
         case "get_report" -> runtime.getReport(arguments);
         case "delete_report" -> runtime.deleteReport(arguments);
         case "list_ai_providers" -> runtime.listAiProviders();
         case "generate_plan" -> runtime.generatePlan(arguments);
         case "improve_plan" -> runtime.improvePlan(arguments);
         case "generate_assertions" -> runtime.generateAssertions(arguments);
         case "suggest_fixtures" -> runtime.suggestFixtures(arguments);
         case "suggest_cleanup" -> runtime.suggestCleanup(arguments);
         case "analyze_failure" -> runtime.analyzeFailure(arguments);
         case "compare_runs" -> runtime.compareRuns(arguments);
         case "list_database_adapters" -> runtime.listDatabaseAdapters();
         default -> throw new IllegalArgumentException("Unknown dbeaver_teststudio action: " + action);
      };
   }

   private void enforceScopes(String action, JsonObject arguments) throws Exception {
      if (Set.of(
         "create_plan", "update_plan", "save_plan", "import_plan", "migrate_plan", "clone_plan", "delete_plan",
         "delete_run", "pin_run", "cleanup_runs", "generate_report", "delete_report"
      ).contains(action)) {
         require(DBeaverMcpScope.WORKSPACE);
      }
      if (Set.of("approve_run", "capture_screenshot").contains(action)) require(DBeaverMcpScope.UI);
      if (Set.of("capture_screenshot").contains(action)) require(DBeaverMcpScope.WORKSPACE);
      if (Set.of("run_plan", "run_step", "retry_failed", "cleanup_run").contains(action)) {
         require(DBeaverMcpScope.QUERY);
      }
      if (action.equals("run_plan") && runtime.approvalRequiresDataWrite(required(arguments, "approval_id"))) {
         require(DBeaverMcpScope.DATA_WRITE);
      }
      if (Set.of("run_status", "run_result", "cancel_run").contains(action)) require(DBeaverMcpScope.WORKSPACE);
   }

   private void require(DBeaverMcpScope... scopes) throws DBeaverMcpAccessDeniedException {
      Set<DBeaverMcpScope> required = Set.of(scopes);
      if (!context.policy().allows(required)) {
         throw new DBeaverMcpAccessDeniedException(
            "MCP policy does not allow scopes: " + required.stream().map(DBeaverMcpScope::id).sorted().toList()
         );
      }
   }

   private static JsonObject schema() {
      JsonObject schema = new JsonObject();
      schema.addProperty("type", "object");
      JsonObject properties = new JsonObject();
      JsonObject action = new JsonObject();
      action.addProperty("type", "string");
      action.addProperty("description", "Test Studio action. Use discover for contracts and safety semantics.");
      JsonArray values = new JsonArray();
      ACTIONS.forEach(values::add);
      action.add("enum", values);
      properties.add("action", action);
      JsonObject arguments = new JsonObject();
      arguments.addProperty("type", "object");
      arguments.addProperty("description", "Action-specific arguments.");
      arguments.addProperty("additionalProperties", true);
      properties.add("arguments", arguments);
      schema.add("properties", properties);
      JsonArray required = new JsonArray();
      required.add("action");
      schema.add("required", required);
      schema.addProperty("additionalProperties", false);
      return schema;
   }

   private static JsonObject discovery() {
      Map<String, String> contracts = new LinkedHashMap<>();
      contracts.put("capabilities", "No arguments. Engine, bridge, limits, assertion/report/AI/database capabilities.");
      contracts.put("validate_plan", "plan object. Validates schema, steps, targets, size, and forbidden secret-like fields.");
      contracts.put("migrate_plan", "plan; optional project, save=false, overwrite=false. Deterministic schema migration preview.");
      contracts.put("create_plan", "project, plan, overwrite=false. Saves Test Studio/Plans/<id>.dbtest.json.");
      contracts.put("update_plan", "project, plan. Saves with Eclipse local history.");
      contracts.put("save_plan", "project, plan, overwrite=false.");
      contracts.put("import_plan", "project, plan, overwrite=false. Canonical JSON importer.");
      contracts.put("export_plan", "project, plan_id. Returns canonical JSON plan.");
      contracts.put("list_plans", "project.");
      contracts.put("get_plan", "project, plan_id.");
      contracts.put("clone_plan", "project, plan_id, new_id, new_name.");
      contracts.put("delete_plan", "project, plan_id. Uses Eclipse resource history.");
      contracts.put("explain_plan", "plan. Deterministic flow summary; no AI required.");
      contracts.put("plan_run", "project plus plan or plan_id. Resolves variables, previews mutations/sandbox, and returns candidate_id+fingerprint.");
      contracts.put("approve_run", "candidate_id, fingerprint, confirm=true. Opens native DBeaver dialog and returns one-time approval_id.");
      contracts.put("cancel_approval", "approval_id or candidate_id in approval_id field.");
      contracts.put("run_plan", "approval_id, fingerprint. Returns shared MCP job_id and run_id; data_write scope required for mutations.");
      contracts.put("run_step", "project, plan or plan_id, step_id, optional include_cleanup=true. Returns a candidate for approval.");
      contracts.put("retry_failed", "project, run_id. Only read-only/idempotent failed steps; returns a new candidate.");
      contracts.put("cleanup_run", "project, run_id. Builds a cleanup-only candidate from the saved plan snapshot.");
      contracts.put("run_status", "job_id.");
      contracts.put("run_result", "job_id. Includes bounded canonical result when terminal.");
      contracts.put("cancel_run", "job_id. Cleanup/finalization still run cooperatively.");
      contracts.put("list_runs", "project, optional limit<=500.");
      contracts.put("get_run", "project, run_id. Canonical manifest/report/cleanup and bounded step evidence.");
      contracts.put("delete_run", "project, run_id.");
      contracts.put("pin_run", "project, run_id, pinned=true.");
      contracts.put("cleanup_runs", "project, keep_last, older_than_days. Pinned runs are retained.");
      contracts.put("capture_screenshot", "project, run_id, optional name. UI+workspace scopes; evidence privacy warning included.");
      contracts.put("list_assertions", "No arguments. Built-in and extension assertion providers.");
      contracts.put("list_report_providers", "No arguments. JSON, JUnit XML, HTML, Markdown, and extensions.");
      contracts.put("generate_report", "project, run_id, format, options, overwrite=false.");
      contracts.put("list_reports", "project, run_id.");
      contracts.put("get_report", "project, run_id, name; maximum one MiB.");
      contracts.put("delete_report", "project, run_id, name.");
      contracts.put("list_ai_providers", "No arguments. Studio remains functional with zero AI providers.");
      contracts.put("generate_plan", "prompt<=8000, optional provider/context<=256KiB. Output is validated draft requiring acceptance.");
      contracts.put("improve_plan", "plan, request, optional provider/context. Returns draft requiring acceptance.");
      contracts.put("generate_assertions", "plan, optional request/provider/context.");
      contracts.put("suggest_fixtures", "plan, optional request/provider/context. Never uses real production data by default.");
      contracts.put("suggest_cleanup", "plan, optional request/provider/context.");
      contracts.put("analyze_failure", "project, run_id, optional provider/context. Advisory, never official test result.");
      contracts.put("compare_runs", "project, left_run_id, right_run_id. Structured deterministic comparison.");
      contracts.put("list_database_adapters", "No arguments. PostgreSQL, MySQL/MariaDB, SQLite and extensions.");
      JsonArray actions = new JsonArray();
      contracts.forEach((name, contract) -> {
         JsonObject item = new JsonObject();
         item.addProperty("action", name);
         item.addProperty("arguments", contract);
         actions.add(item);
      });
      JsonObject result = new JsonObject();
      result.addProperty("facade", "dbeaver_teststudio");
      result.addProperty("count", actions.size());
      result.add("actions", actions);
      return result;
   }

   private static String required(JsonObject object, String name) {
      JsonElement value = object.get(name);
      if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
         throw new IllegalArgumentException(name + " is required");
      }
      return value.getAsString();
   }

   private static JsonObject requiredObject(JsonObject object, String name) {
      JsonElement value = object.get(name);
      if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " object is required");
      return value.getAsJsonObject();
   }

   private static JsonObject object(JsonObject object, String name) {
      JsonElement value = object.get(name);
      return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
   }

   private static boolean bool(JsonObject object, String name, boolean fallback) {
      JsonElement value = object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
   }

   private static int integer(JsonObject object, String name, int fallback, int minimum, int maximum) {
      JsonElement value = object.get(name);
      if (value == null || !value.isJsonPrimitive()) return fallback;
      int parsed = value.getAsInt();
      if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
      return parsed;
   }
}
