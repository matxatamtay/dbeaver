package org.jkiss.dbeaver.teststudio.db.mysql;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.jkiss.dbeaver.teststudio.spi.DatabaseAdapter;

public final class MySqlDatabaseAdapter implements DatabaseAdapter {
   @Override
   public String id() {
      return "mysql";
   }

   @Override
   public boolean supports(String productName, String driverId) {
      String value = (productName + " " + driverId).toLowerCase(Locale.ENGLISH);
      return value.contains("mysql") || value.contains("mariadb");
   }

   @Override
   public JsonObject capabilities() {
      JsonObject result = new JsonObject();
      result.addProperty("transaction", true);
      result.addProperty("ddl_rollback", false);
      result.addProperty("savepoint", true);
      result.addProperty("temp_schema", false);
      result.addProperty("sequence_rollback", false);
      return result;
   }

   @Override
   public String quoteIdentifier(String identifier) {
      validate(identifier);
      return "`" + identifier.replace("`", "``") + "`";
   }

   @Override
   public String literal(JsonElement value) {
      if (value == null || value.isJsonNull()) return "NULL";
      if (value.isJsonPrimitive()) {
         var primitive = value.getAsJsonPrimitive();
         if (primitive.isBoolean()) return primitive.getAsBoolean() ? "1" : "0";
         if (primitive.isNumber()) return primitive.getAsString();
         return quoteString(primitive.getAsString());
      }
      return quoteString(value.toString());
   }

   private static String quoteString(String value) {
      return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
   }

   private static void validate(String identifier) {
      if (identifier == null || identifier.isBlank() || identifier.indexOf('\0') >= 0) {
         throw new IllegalArgumentException("Invalid identifier");
      }
   }
}
