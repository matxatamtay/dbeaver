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

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import org.jkiss.dbeaver.model.DBPDataSource;

final class DatabaseIntrospectors {
   private static final List<DatabaseIntrospector> INTROSPECTORS = List.of(
      named("sqlite", List.of("sqlite"), "EXPLAIN QUERY PLAN ", null, capabilities(true, true, false, false, false, true, false)),
      named(
         "postgresql",
         List.of("postgresql", "postgres"),
         "EXPLAIN (FORMAT JSON) ",
         "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ",
         capabilities(true, true, true, true, true, true, true)
      ),
      named("mysql", List.of("mysql", "mariadb"), "EXPLAIN FORMAT=JSON ", "EXPLAIN ANALYZE ", capabilities(true, true, true, true, false, true, false)),
      unsupportedExplain("sqlserver", List.of("microsoft sql server", "sql server"), capabilities(true, true, true, true, false, true, false)),
      unsupportedExplain("oracle", List.of("oracle"), capabilities(true, true, true, true, false, true, false)),
      named("generic", List.of(), "EXPLAIN ", "EXPLAIN ANALYZE ", capabilities(true, true, false, false, false, true, false))
   );

   private DatabaseIntrospectors() {
   }

   static DatabaseIntrospector forDataSource(DBPDataSource dataSource) {
      for (DatabaseIntrospector introspector : INTROSPECTORS) {
         if (introspector.supports(dataSource)) {
            return introspector;
         }
      }

      return INTROSPECTORS.getLast();
   }

   private static DatabaseIntrospector named(
      final String id, final List<String> products, final String explainPrefix, final String analyzePrefix, final JsonObject capabilities
   ) {
      return new DatabaseIntrospector() {
         @Override
         public String id() {
            return id;
         }

         @Override
         public boolean supports(DBPDataSource dataSource) {
            if (products.isEmpty()) {
               return true;
            } else {
               String product = dataSource.getInfo().getDatabaseProductName().toLowerCase(Locale.ENGLISH);
               return products.stream().anyMatch(product::contains);
            }
         }

         @Override
         public JsonObject capabilities() {
            return capabilities.deepCopy();
         }

         @Override
         public String explainSql(String sql, boolean analyze) {
            if (analyze && analyzePrefix == null) {
               throw new IllegalArgumentException(id + " adapter does not support safe EXPLAIN ANALYZE generation");
            } else {
               return (analyze ? analyzePrefix : explainPrefix) + sql;
            }
         }

         @Override
         public boolean supportsExplainAnalyze() {
            return analyzePrefix != null;
         }
      };
   }

   private static DatabaseIntrospector unsupportedExplain(final String id, final List<String> products, final JsonObject capabilities) {
      return new DatabaseIntrospector() {
         @Override
         public String id() {
            return id;
         }

         @Override
         public boolean supports(DBPDataSource dataSource) {
            String product = dataSource.getInfo().getDatabaseProductName().toLowerCase(Locale.ENGLISH);
            return products.stream().anyMatch(product::contains);
         }

         @Override
         public JsonObject capabilities() {
            return capabilities.deepCopy();
         }

         @Override
         public String explainSql(String sql, boolean analyze) {
            throw new IllegalArgumentException(id + " execution-plan collection needs a database-specific session workflow and is not enabled by this adapter");
         }

         @Override
         public boolean supportsExplainAnalyze() {
            return false;
         }
      };
   }

   private static JsonObject capabilities(
      boolean objectDiscovery,
      boolean ddl,
      boolean triggerMetadata,
      boolean routineDependencies,
      boolean permissions,
      boolean transactionSimulation,
      boolean structuredExplain
   ) {
      JsonObject result = new JsonObject();
      result.addProperty("object_discovery", objectDiscovery);
      result.addProperty("ddl", ddl);
      result.addProperty("trigger_metadata", triggerMetadata);
      result.addProperty("routine_dependencies", routineDependencies);
      result.addProperty("permissions", permissions);
      result.addProperty("transaction_simulation", transactionSimulation);
      result.addProperty("structured_explain", structuredExplain);
      result.addProperty("column_lineage", "partial");
      return result;
   }
}
