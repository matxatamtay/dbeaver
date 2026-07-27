/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.struct.DBSObject;

final class PostgreSqlSecurityService {
   private final DBeaverSqlService sql;
   private final DBeaverObjectService objects;

   PostgreSqlSecurityService(DBeaverSqlService sql, DBeaverObjectService objects) {
      this.sql = sql;
      this.objects = objects;
   }

   boolean supports(DBeaverConnectionService.ResolvedConnection connection) {
      return connection.dataSource().getInfo().getDatabaseProductName().toLowerCase(Locale.ENGLISH).contains("postgres");
   }

   JsonObject inspect(DBeaverConnectionService.ResolvedConnection connection, JsonObject arguments) throws Exception {
      JsonObject result = new JsonObject();
      result.add("connection", DBeaverConnectionService.connectionPayload(connection.container()));
      DBPDataSourceInfo info = connection.dataSource().getInfo();
      result.addProperty("connection_read_only", connection.container().isConnectionReadOnly());
      result.addProperty("database_read_only_data", info.isReadOnlyData());
      result.addProperty("database_read_only_metadata", info.isReadOnlyMetaData());
      JsonObject principalQuery = this.sql
         .query(
            connection,
            "SELECT current_user AS current_user,\n       session_user AS session_user,\n       current_role AS current_role,\n       r.rolsuper,\n       r.rolinherit,\n       r.rolcreaterole,\n       r.rolcreatedb,\n       r.rolcanlogin,\n       r.rolreplication,\n       r.rolbypassrls\n  FROM pg_roles r\n WHERE r.rolname = current_user\n",
            1,
            30
         );
      JsonObject principal = firstRow(principalQuery);
      if (principal != null) {
         result.add("principal", principal.deepCopy());
         result.addProperty("effective_principal", stringValue(principal, "current_user"));
      } else {
         result.addProperty("effective_principal", "unavailable");
      }

      result.add(
         "roles",
         rows(
            this.sql
               .query(
                  connection,
                  "WITH RECURSIVE role_tree(role_oid, role_name, depth, path) AS (\n    SELECT r.oid, r.rolname, 0, ARRAY[r.oid]\n      FROM pg_roles r\n     WHERE r.rolname = current_user\n    UNION ALL\n    SELECT parent.oid, parent.rolname, child.depth + 1, child.path || parent.oid\n      FROM role_tree child\n      JOIN pg_auth_members membership ON membership.member = child.role_oid\n      JOIN pg_roles parent ON parent.oid = membership.roleid\n     WHERE NOT parent.oid = ANY(child.path)\n)\nSELECT role_name,\n       depth,\n       pg_has_role(current_user, role_name, 'USAGE') AS usable,\n       pg_has_role(current_user, role_name, 'MEMBER') AS member\n  FROM role_tree\n ORDER BY depth, role_name\n",
                  200,
                  30
               )
         )
      );
      JsonObject databasePrivileges = firstRow(
         this.sql
            .query(
               connection,
               "SELECT current_database() AS database,\n       has_database_privilege(current_user, current_database(), 'CONNECT') AS can_connect,\n       has_database_privilege(current_user, current_database(), 'CREATE') AS can_create,\n       has_database_privilege(current_user, current_database(), 'TEMPORARY') AS can_create_temporary\n",
               1,
               30
            )
      );
      result.add("database_privileges", databasePrivileges == null ? new JsonObject() : databasePrivileges.deepCopy());
      result.add(
         "schema_privileges",
         rows(
            this.sql
               .query(
                  connection,
                  "SELECT n.nspname AS schema,\n       pg_get_userbyid(n.nspowner) AS owner,\n       has_schema_privilege(current_user, n.oid, 'USAGE') AS can_use,\n       has_schema_privilege(current_user, n.oid, 'CREATE') AS can_create\n  FROM pg_namespace n\n WHERE n.nspname <> 'information_schema'\n   AND left(n.nspname, 3) <> 'pg_'\n ORDER BY n.nspname\n",
                  500,
                  30
               )
         )
      );
      JsonArray objectGrants = new JsonArray();
      JsonArray columnGrants = new JsonArray();
      JsonArray rowLevelSecurity = new JsonArray();
      JsonArray routineSecurity = new JsonArray();
      if (hasObjectSelector(arguments)) {
         DBSObject object = this.objects.resolve(connection, arguments);
         JsonObject identity = DBeaverObjectService.identity(object);
         result.add("object", identity.deepCopy());
         String schema = stringValue(identity, "schema");
         String name = stringValue(identity, "name");
         String type = stringValue(identity, "object_type");
         if (!schema.isBlank() && !name.isBlank()) {
            if (type.equals("table") || type.equals("view")) {
               this.inspectRelation(connection, schema, name, objectGrants, columnGrants, rowLevelSecurity, result);
            } else if (type.equals("function") || type.equals("procedure")) {
               this.inspectRoutine(connection, schema, name, objectGrants, routineSecurity, result);
            } else if (type.equals("schema")) {
               result.add(
                  "selected_schema_privileges",
                  rows(
                     this.sql
                        .query(
                           connection,
                           "SELECT n.nspname AS schema, pg_get_userbyid(n.nspowner) AS owner, has_schema_privilege(current_user, n.oid, 'USAGE') AS can_use, has_schema_privilege(current_user, n.oid, 'CREATE') AS can_create FROM pg_namespace n WHERE n.nspname = '"
                              + literal(schema)
                              + "'",
                           10,
                           30
                        )
                  )
               );
            }
         }
      }

      result.add("object_grants", objectGrants);
      result.add("column_grants", columnGrants);
      result.add("row_level_security", rowLevelSecurity);
      result.add("routine_security", routineSecurity);
      JsonObject coverage = new JsonObject();
      coverage.addProperty("read_only_state", "exact");
      coverage.addProperty("principal_and_roles", "exact_postgresql_catalog_queries");
      coverage.addProperty("database_and_schema_privileges", "exact_postgresql_privilege_checks");
      coverage.addProperty("object_grants", hasObjectSelector(arguments) ? "exact_for_selected_object" : "not_requested");
      coverage.addProperty("row_level_security", hasObjectSelector(arguments) ? "exact_for_selected_relation" : "not_requested");
      result.add("coverage", coverage);
      JsonArray blindSpots = new JsonArray();
      blindSpots.add(
         "Privileges can still depend on SET ROLE, session authorization changes, SECURITY DEFINER execution, and application-managed session settings."
      );
      blindSpots.add("External authorization systems and application-layer policies are outside PostgreSQL catalogs.");
      result.add("blind_spots", blindSpots);
      return result;
   }

   private void inspectRelation(
      DBeaverConnectionService.ResolvedConnection connection,
      String schema,
      String name,
      JsonArray objectGrants,
      JsonArray columnGrants,
      JsonArray rowLevelSecurity,
      JsonObject result
   ) throws Exception {
      String relation = qualifiedLiteral(schema, name);
      String relationSql = "SELECT n.nspname AS schema, c.relname AS relation, c.relkind::text AS relation_kind, pg_get_userbyid(c.relowner) AS owner, c.relrowsecurity AS rls_enabled, c.relforcerowsecurity AS rls_forced, row_security_active(c.oid) AS rls_active_for_current_user, has_table_privilege(current_user, c.oid, 'SELECT') AS can_select, has_table_privilege(current_user, c.oid, 'INSERT') AS can_insert, has_table_privilege(current_user, c.oid, 'UPDATE') AS can_update, has_table_privilege(current_user, c.oid, 'DELETE') AS can_delete, has_table_privilege(current_user, c.oid, 'TRUNCATE') AS can_truncate, has_table_privilege(current_user, c.oid, 'REFERENCES') AS can_reference, has_table_privilege(current_user, c.oid, 'TRIGGER') AS can_create_trigger FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '"
         + literal(schema)
         + "' AND c.relname = '"
         + literal(name)
         + "'";
      JsonObject relationSecurity = firstRow(this.sql.query(connection, relationSql, 20, 30));
      if (relationSecurity != null) {
         result.add("relation_security", relationSecurity.deepCopy());
      }

      appendRows(
         objectGrants,
         this.sql
            .query(
               connection,
               "SELECT grantee, privilege_type, is_grantable, grantor FROM information_schema.table_privileges WHERE table_schema = '"
                  + literal(schema)
                  + "' AND table_name = '"
                  + literal(name)
                  + "' ORDER BY grantee, privilege_type",
               500,
               30
            )
      );
      appendRows(
         columnGrants,
         this.sql
            .query(
               connection,
               "SELECT grantee, column_name, privilege_type, is_grantable, grantor FROM information_schema.column_privileges WHERE table_schema = '"
                  + literal(schema)
                  + "' AND table_name = '"
                  + literal(name)
                  + "' ORDER BY grantee, column_name, privilege_type",
               1000,
               30
            )
      );
      appendRows(
         rowLevelSecurity,
         this.sql
            .query(
               connection,
               "SELECT schemaname AS schema, tablename AS relation, policyname, permissive, roles::text, cmd, qual, with_check FROM pg_policies WHERE schemaname = '"
                  + literal(schema)
                  + "' AND tablename = '"
                  + literal(name)
                  + "' ORDER BY policyname",
               200,
               30
            )
      );
      result.addProperty("selected_relation", relation);
   }

   private void inspectRoutine(
      DBeaverConnectionService.ResolvedConnection connection, String schema, String name, JsonArray objectGrants, JsonArray routineSecurity, JsonObject result
   ) throws Exception {
      JsonObject routines = this.sql
         .query(
            connection,
            "SELECT n.nspname AS schema, p.proname AS routine, p.prokind::text AS routine_kind, pg_get_function_identity_arguments(p.oid) AS identity_arguments, pg_get_userbyid(p.proowner) AS owner, p.prosecdef AS security_definer, p.proleakproof AS leakproof, p.provolatile::text AS volatility, has_function_privilege(current_user, p.oid, 'EXECUTE') AS can_execute FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='"
               + literal(schema)
               + "' AND p.proname='"
               + literal(name)
               + "' ORDER BY identity_arguments",
            100,
            30
         );
      appendRows(routineSecurity, routines);
      appendRows(
         objectGrants,
         this.sql
            .query(
               connection,
               "SELECT grantee, routine_name, privilege_type, is_grantable, grantor, specific_name FROM information_schema.routine_privileges WHERE routine_schema='"
                  + literal(schema)
                  + "' AND routine_name='"
                  + literal(name)
                  + "' ORDER BY grantee, specific_name, privilege_type",
               500,
               30
            )
      );
      result.addProperty("selected_routine", schema + "." + name);
   }

   private static boolean hasObjectSelector(JsonObject arguments) {
      return !McpJson.getString(arguments, "object_id", "").isBlank()
         || !McpJson.getString(arguments, "qualified_name", "").isBlank()
         || !McpJson.getString(arguments, "name", "").isBlank();
   }

   private static JsonArray rows(JsonObject queryResult) {
      JsonArray rows = queryResult.getAsJsonArray("rows");
      return rows == null ? new JsonArray() : rows.deepCopy();
   }

   private static void appendRows(JsonArray target, JsonObject queryResult) {
      JsonArray values = queryResult.getAsJsonArray("rows");
      if (values != null) {
         for (JsonElement value : values) {
            target.add(value.deepCopy());
         }
      }
   }

   private static JsonObject firstRow(JsonObject queryResult) {
      JsonArray values = queryResult.getAsJsonArray("rows");
      return values != null && !values.isEmpty() && values.get(0).isJsonObject() ? values.get(0).getAsJsonObject() : null;
   }

   private static String stringValue(JsonObject object, String key) {
      JsonElement value = object.get(key);
      return value != null && !value.isJsonNull() ? value.getAsString() : "";
   }

   private static String literal(String value) {
      return value.replace("'", "''");
   }

   private static String qualifiedLiteral(String schema, String name) {
      return schema + "." + name;
   }
}
