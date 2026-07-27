package org.jkiss.dbeaver.teststudio.tests;

import com.google.gson.*;
import java.util.List;
import org.jkiss.dbeaver.teststudio.ai.HeuristicAiProvider;
import org.jkiss.dbeaver.teststudio.db.mysql.MySqlDatabaseAdapter;
import org.jkiss.dbeaver.teststudio.db.postgresql.PostgreSqlDatabaseAdapter;
import org.jkiss.dbeaver.teststudio.db.sqlite.SqliteDatabaseAdapter;
import org.jkiss.dbeaver.teststudio.spi.DatabaseAdapter;

public final class AdapterAndAiTest {
   public static void main(String[] args) throws Exception {
      testAdapters();
      testAiFallback();
      System.out.println("AdapterAndAiTest passed");
   }

   private static void testAdapters() {
      DatabaseAdapter postgres = new PostgreSqlDatabaseAdapter();
      String sql = postgres.insertSql(
         "public.users",
         List.of("email", "active"),
         List.of(new JsonPrimitive("o'hara@example.invalid"), new JsonPrimitive(true))
      );
      check(sql.equals("INSERT INTO \"public\".\"users\" (\"email\", \"active\") VALUES ('o''hara@example.invalid', TRUE)"), "PostgreSQL insert SQL must quote qualified identifiers and values: " + sql);
      check(postgres.supports("PostgreSQL", "postgres-jdbc"), "PostgreSQL adapter matching");
      check(!postgres.capabilities().get("sequence_rollback").getAsBoolean(), "sequence rollback warning must be explicit");

      DatabaseAdapter mysql = new MySqlDatabaseAdapter();
      sql = mysql.insertSql("app.users", List.of("name"), List.of(new JsonPrimitive("a\\b'c")));
      check(sql.equals("INSERT INTO `app`.`users` (`name`) VALUES ('a\\\\b''c')"), "MySQL escaping must be deterministic: " + sql);
      check(mysql.supports("MariaDB", "maria"), "MariaDB should use MySQL adapter");
      check(!mysql.capabilities().get("ddl_rollback").getAsBoolean(), "MySQL DDL rollback must be marked unsupported");

      DatabaseAdapter sqlite = new SqliteDatabaseAdapter();
      check(sqlite.supports("SQLite", "sqlite-jdbc"), "SQLite adapter matching");
      check("\"a\"\"b\"".equals(sqlite.quoteIdentifier("a\"b")), "SQLite identifier escaping");
   }

   private static void testAiFallback() throws Exception {
      HeuristicAiProvider ai = new HeuristicAiProvider();
      JsonObject context = new JsonObject();
      context.addProperty("project", "General");
      context.addProperty("connection", "local");
      JsonObject generated = ai.generatePlan("Check database version", context);
      JsonObject plan = generated.getAsJsonObject("plan");
      check("1.0".equals(plan.get("schema_version").getAsString()), "AI plan schema version");
      check(plan.getAsJsonArray("steps").get(0).getAsJsonObject().get("sql").getAsString().contains("version"), "AI fallback should choose bounded version query");
      check(generated.get("requires_review").getAsBoolean(), "AI output must require review");
      JsonObject analysis = ai.analyzeFailure(new JsonObject(), new JsonObject());
      check(!analysis.get("official_result").getAsBoolean(), "AI failure analysis must never be official result");
      check(!ai.capabilities().get("network").getAsBoolean(), "fallback AI must be offline");
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
