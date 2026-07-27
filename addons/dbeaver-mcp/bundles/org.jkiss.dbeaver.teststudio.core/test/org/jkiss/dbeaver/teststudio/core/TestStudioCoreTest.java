package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;

public final class TestStudioCoreTest {
   public static void main(String[] args) throws Exception {
      testPlanValidationAndMigration();
      testVariablesAndFingerprint();
      testSqlSafety();
      testApprovals();
      testAssertions();
      testReports();
      System.out.println("TestStudioCoreTest passed");
   }

   private static void testPlanValidationAndMigration() {
      TestPlanValidator validator = new TestPlanValidator();
      JsonObject plan = plan();
      JsonObject valid = validator.validate(plan);
      check(valid.get("valid").getAsBoolean(), "canonical plan must validate: " + valid);
      check(valid.get("fingerprint").getAsString().length() == 64, "plan fingerprint must be SHA-256");

      JsonObject secret = plan.deepCopy();
      secret.addProperty("password", "not-allowed");
      JsonObject invalid = validator.validate(secret);
      check(!invalid.get("valid").getAsBoolean(), "secret-like fields must be rejected");

      JsonObject legacy = new JsonObject();
      legacy.addProperty("schema_version", "0.9");
      legacy.addProperty("id", "legacy");
      legacy.addProperty("name", "Legacy");
      legacy.addProperty("connection", "local");
      JsonArray cases = new JsonArray();
      cases.add(queryStep("legacy-query", "SELECT 1"));
      legacy.add("cases", cases);
      JsonObject migrated = new TestPlanMigrator().migrate(legacy);
      check(migrated.get("changed").getAsBoolean(), "legacy plan must migrate");
      check("1.0".equals(migrated.getAsJsonObject("plan").get("schema_version").getAsString()), "schema version must become 1.0");
      check(validator.validate(migrated.getAsJsonObject("plan")).get("valid").getAsBoolean(), "migrated plan must validate");
   }

   private static void testVariablesAndFingerprint() {
      JsonObject definitions = new JsonObject();
      JsonObject email = new JsonObject();
      email.addProperty("generator", "unique_email");
      definitions.add("email", email);
      JsonObject fixed = new JsonObject();
      fixed.addProperty("value", 42);
      definitions.add("answer", fixed);
      JsonObject resolved = new VariableResolver().resolve(definitions);
      check(resolved.get("email").getAsString().endsWith("@example.invalid"), "generated email must use reserved invalid domain");
      check(resolved.get("answer").getAsInt() == 42, "fixed variable must survive");
      JsonObject value = new JsonObject();
      value.addProperty("sql", "SELECT '${email}' AS email, ${answer} AS answer");
      JsonObject substituted = StudioJson.substitute(value, resolved).getAsJsonObject();
      check(substituted.get("sql").getAsString().contains("42"), "variable substitution must resolve numbers");
      check(StudioJson.fingerprint(value).equals(StudioJson.fingerprint(value.deepCopy())), "canonical fingerprint must be stable");
   }

   private static void testSqlSafety() {
      check(StudioSqlSafety.isReadOnly("SELECT 1"), "SELECT must be read-only");
      check(StudioSqlSafety.isReadOnly("WITH x AS (SELECT 1) SELECT * FROM x"), "read-only CTE must pass");
      check(!StudioSqlSafety.isReadOnly("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"), "mutating CTE must fail");
      check(!StudioSqlSafety.isReadOnly("UPDATE users SET active=true"), "UPDATE must fail");
      check(StudioSqlSafety.isReadOnly("SELECT 'delete from users' AS text"), "keywords inside strings must not mutate classification");
   }

   private static void testApprovals() {
      ApprovalStore store = new ApprovalStore();
      JsonObject plan = plan();
      JsonObject variables = new JsonObject();
      variables.addProperty("id", "abc");
      JsonObject token = store.create("General", "fingerprint", plan, variables, true);
      String id = token.get("approval_id").getAsString();
      check(store.peek(id).requiresDataWrite(), "risk must stay bound to approval");
      boolean mismatch = false;
      try { store.consume(id, "changed"); } catch (IllegalArgumentException expected) { mismatch = true; }
      check(mismatch, "changed fingerprint must be rejected");
      boolean oneTime = false;
      try { store.peek(id); } catch (IllegalArgumentException expected) { oneTime = true; }
      check(oneTime, "failed consume still consumes one-time token");
   }

   private static void testAssertions() {
      JsonArray rows = new JsonArray();
      JsonObject first = new JsonObject();
      first.addProperty("id", 1);
      first.addProperty("name", "alpha");
      rows.add(first);
      JsonObject second = new JsonObject();
      second.addProperty("id", 2);
      second.addProperty("name", "beta");
      rows.add(second);
      JsonObject query = new JsonObject();
      query.addProperty("row_count", 2);
      query.add("rows", rows);

      JsonObject rowCount = new JsonObject();
      rowCount.addProperty("expected", 2);
      JsonObject result = new BuiltinAssertionProvider("row_count").evaluate(new org.jkiss.dbeaver.teststudio.spi.AssertionContext(query, new JsonObject(), new JsonObject(), null), rowCount);
      check(result.get("passed").getAsBoolean(), "row_count assertion must pass");

      JsonObject notNull = new JsonObject();
      notNull.addProperty("column", "name");
      result = new BuiltinAssertionProvider("column_not_null").evaluate(new org.jkiss.dbeaver.teststudio.spi.AssertionContext(query, new JsonObject(), new JsonObject(), null), notNull);
      check(result.get("passed").getAsBoolean(), "column_not_null assertion must pass");

      JsonObject range = new JsonObject();
      range.addProperty("minimum", 1);
      range.addProperty("maximum", 3);
      result = new BuiltinAssertionProvider("numeric_range").evaluate(new org.jkiss.dbeaver.teststudio.spi.AssertionContext(new JsonPrimitive(2), new JsonObject(), new JsonObject(), null), range);
      check(result.get("passed").getAsBoolean(), "numeric range must pass");
   }

   private static void testReports() throws Exception {
      JsonObject run = new JsonObject();
      run.addProperty("run_id", "run-1");
      run.addProperty("plan_id", "plan-1");
      run.addProperty("name", "Report test");
      run.addProperty("state", "failed");
      run.addProperty("passed", false);
      JsonArray steps = new JsonArray();
      JsonObject passed = new JsonObject();
      passed.addProperty("id", "one");
      passed.addProperty("type", "query");
      passed.addProperty("status", "passed");
      passed.addProperty("passed", true);
      passed.addProperty("elapsed_ms", 10);
      steps.add(passed);
      JsonObject failed = new JsonObject();
      failed.addProperty("id", "two");
      failed.addProperty("type", "assert");
      failed.addProperty("status", "failed");
      failed.addProperty("passed", false);
      failed.addProperty("message", "expected mismatch");
      steps.add(failed);
      run.add("steps", steps);
      run.add("cleanup", new JsonObject());

      String junit = new String(new JUnitReportProvider().generate(run, new JsonObject()), java.nio.charset.StandardCharsets.UTF_8);
      check(junit.contains("tests=\"2\""), "JUnit must report two tests");
      check(junit.contains("<failure"), "JUnit must include failure");
      String html = new String(new HtmlReportProvider().generate(run, new JsonObject()), java.nio.charset.StandardCharsets.UTF_8);
      check(html.contains("<!doctype html>"), "HTML report must be standalone");
      String markdown = new String(new MarkdownReportProvider().generate(run, new JsonObject()), java.nio.charset.StandardCharsets.UTF_8);
      check(markdown.contains("## Steps"), "Markdown must include steps");
      JsonObject parsed = JsonParser.parseString(new String(new JsonReportProvider().generate(run, new JsonObject()), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      check("run-1".equals(parsed.get("run_id").getAsString()), "JSON report must round-trip");
   }

   private static JsonObject plan() {
      JsonObject plan = new JsonObject();
      plan.addProperty("schema_version", "1.0");
      plan.addProperty("id", "sample-plan");
      plan.addProperty("name", "Sample plan");
      JsonObject target = new JsonObject();
      target.addProperty("connection", "local");
      target.addProperty("project", "General");
      JsonObject targets = new JsonObject();
      targets.add("default", target);
      plan.add("targets", targets);
      plan.add("variables", new JsonObject());
      plan.add("setup", new JsonArray());
      JsonArray steps = new JsonArray();
      steps.add(queryStep("health", "SELECT 1 AS ok"));
      plan.add("steps", steps);
      plan.add("cleanup", new JsonArray());
      return plan;
   }

   private static JsonObject queryStep(String id, String sql) {
      JsonObject step = new JsonObject();
      step.addProperty("id", id);
      step.addProperty("type", "query");
      step.addProperty("sql", sql);
      JsonArray assertions = new JsonArray();
      JsonObject assertion = new JsonObject();
      assertion.addProperty("type", "row_count");
      assertion.addProperty("expected", 1);
      assertions.add(assertion);
      step.add("assertions", assertions);
      return step;
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
