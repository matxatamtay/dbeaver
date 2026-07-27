package org.jkiss.dbeaver.teststudio.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.jkiss.dbeaver.teststudio.db.mysql.MySqlDatabaseAdapter;
import org.jkiss.dbeaver.teststudio.db.postgresql.PostgreSqlDatabaseAdapter;
import org.jkiss.dbeaver.teststudio.spi.DatabaseAdapter;

public final class DatabaseTransactionE2ETest {
   public static void main(String[] args) throws Exception {
      String kind = required("TESTSTUDIO_DB_KIND");
      String url = required("TESTSTUDIO_DB_URL");
      String user = required("TESTSTUDIO_DB_USER");
      String password = required("TESTSTUDIO_DB_PASSWORD");
      DatabaseAdapter adapter = switch (kind) {
         case "postgresql" -> new PostgreSqlDatabaseAdapter();
         case "mysql" -> new MySqlDatabaseAdapter();
         default -> throw new IllegalArgumentException("Unsupported E2E database kind: " + kind);
      };
      try (Connection connection = DriverManager.getConnection(url, user, password)) {
         testRollback(connection, adapter, kind);
         testDdlSemantics(connection, adapter, kind);
         testFailureCleanup(connection, adapter, kind);
      }
      System.out.println("DatabaseTransactionE2ETest passed for " + kind);
   }

   private static void testRollback(Connection connection, DatabaseAdapter adapter, String kind) throws Exception {
      execute(connection, "DROP TABLE IF EXISTS studio_fixture");
      execute(connection, "CREATE TABLE studio_fixture (id INTEGER PRIMARY KEY, name VARCHAR(120) NOT NULL)");
      connection.setAutoCommit(false);
      String insert = adapter.insertSql(
         "studio_fixture",
         List.of("id", "name"),
         List.of(new JsonPrimitive(1), new JsonPrimitive("rollback-check"))
      );
      execute(connection, insert);
      check(count(connection, "SELECT COUNT(*) FROM studio_fixture") == 1, kind + " fixture must exist inside transaction");
      connection.rollback();
      connection.setAutoCommit(true);
      check(count(connection, "SELECT COUNT(*) FROM studio_fixture") == 0, kind + " fixture must be gone after rollback");
   }

   private static void testDdlSemantics(Connection connection, DatabaseAdapter adapter, String kind) throws Exception {
      execute(connection, "DROP TABLE IF EXISTS studio_ddl_probe");
      connection.setAutoCommit(false);
      execute(connection, "CREATE TABLE studio_ddl_probe (id INTEGER PRIMARY KEY)");
      connection.rollback();
      connection.setAutoCommit(true);
      boolean exists = tableExists(connection, "studio_ddl_probe");
      boolean declaredRollback = adapter.capabilities().get("ddl_rollback").getAsBoolean();
      check(exists != declaredRollback, kind + " DDL rollback capability must match observed behavior; exists=" + exists + " declaredRollback=" + declaredRollback);
      execute(connection, "DROP TABLE IF EXISTS studio_ddl_probe");
   }

   private static void testFailureCleanup(Connection connection, DatabaseAdapter adapter, String kind) throws Exception {
      execute(connection, "DELETE FROM studio_fixture");
      connection.setAutoCommit(false);
      try {
         execute(connection, adapter.insertSql(
            "studio_fixture",
            List.of("id", "name"),
            List.of(new JsonPrimitive(2), new JsonPrimitive("failure-check"))
         ));
         execute(connection, "INSERT INTO missing_teststudio_table(id) VALUES (1)");
         throw new AssertionError("Expected database operation failure");
      } catch (SQLException expected) {
         connection.rollback();
      } finally {
         connection.setAutoCommit(true);
      }
      check(count(connection, "SELECT COUNT(*) FROM studio_fixture") == 0, kind + " cleanup must preserve original empty state after failure");
      execute(connection, "DROP TABLE IF EXISTS studio_fixture");
   }

   private static boolean tableExists(Connection connection, String table) throws Exception {
      try (ResultSet result = connection.getMetaData().getTables(null, null, table, new String[] {"TABLE"})) {
         if (result.next()) return true;
      }
      try (ResultSet result = connection.getMetaData().getTables(null, null, table.toUpperCase(), new String[] {"TABLE"})) {
         return result.next();
      }
   }

   private static long count(Connection connection, String sql) throws Exception {
      try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
         if (!result.next()) throw new IllegalStateException("Count query returned no rows");
         return result.getLong(1);
      }
   }

   private static void execute(Connection connection, String sql) throws Exception {
      try (Statement statement = connection.createStatement()) {
         statement.execute(sql);
      }
   }

   private static String required(String name) {
      String value = System.getenv(name);
      if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
      return value;
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
