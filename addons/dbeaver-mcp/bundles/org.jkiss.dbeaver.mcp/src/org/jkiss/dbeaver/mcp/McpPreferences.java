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

import java.io.IOException;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;

final class McpPreferences {
   static final String PREF_ENABLED = "dbeaver.mcp.enabled";
   static final String PREF_PORT = "dbeaver.mcp.port";
   static final String PREF_AUTH_TOKEN = "dbeaver.mcp.authToken";
   static final String ENV_ENABLED = "DBEAVER_MCP_ENABLED";
   static final String ENV_PORT = "DBEAVER_MCP_PORT";
   static final String ENV_AUTH_TOKEN = "DBEAVER_MCP_AUTH_TOKEN";
   static final boolean DEFAULT_ENABLED = true;
   static final int DEFAULT_PORT = 3846;
   static final String DEFAULT_AUTH_TOKEN = "";

   private McpPreferences() {
   }

   static void initializeDefaults() {
      DBPPreferenceStore store = store();
      store.setDefault("dbeaver.mcp.enabled", true);
      store.setDefault("dbeaver.mcp.port", 3846);
      store.setDefault("dbeaver.mcp.authToken", "");
   }

   static McpPreferences.Config effectiveConfig() {
      initializeDefaults();
      return new McpPreferences.Config(
         readBoolean("dbeaver.mcp.enabled", "DBEAVER_MCP_ENABLED", store().getBoolean("dbeaver.mcp.enabled")),
         readInt("dbeaver.mcp.port", "DBEAVER_MCP_PORT", store().getInt("dbeaver.mcp.port"), 1, 65535),
         readString("dbeaver.mcp.authToken", "DBEAVER_MCP_AUTH_TOKEN", valueOrEmpty(store().getString("dbeaver.mcp.authToken")))
      );
   }

   static McpPreferences.Config storedConfig() {
      initializeDefaults();
      DBPPreferenceStore store = store();
      return new McpPreferences.Config(
         store.getBoolean("dbeaver.mcp.enabled"), validPort(store.getInt("dbeaver.mcp.port")), valueOrEmpty(store.getString("dbeaver.mcp.authToken"))
      );
   }

   static void save(boolean enabled, int port, String authToken) throws IOException {
      DBPPreferenceStore store = store();
      store.setValue("dbeaver.mcp.enabled", enabled);
      store.setValue("dbeaver.mcp.port", validPort(port));
      store.setValue("dbeaver.mcp.authToken", authToken);
      store.save();
   }

   static boolean isEnabledExternallyOverridden() {
      return isExternallyOverridden("dbeaver.mcp.enabled", "DBEAVER_MCP_ENABLED");
   }

   static boolean isPortExternallyOverridden() {
      return isExternallyOverridden("dbeaver.mcp.port", "DBEAVER_MCP_PORT");
   }

   static boolean isAuthTokenExternallyOverridden() {
      return isExternallyOverridden("dbeaver.mcp.authToken", "DBEAVER_MCP_AUTH_TOKEN");
   }

   static boolean hasExternalOverrides() {
      return isEnabledExternallyOverridden() || isPortExternallyOverridden() || isAuthTokenExternallyOverridden();
   }

   static String externalOverrideDescription() {
      StringBuilder result = new StringBuilder();
      appendOverride(result, isEnabledExternallyOverridden(), "dbeaver.mcp.enabled", "DBEAVER_MCP_ENABLED");
      appendOverride(result, isPortExternallyOverridden(), "dbeaver.mcp.port", "DBEAVER_MCP_PORT");
      appendOverride(result, isAuthTokenExternallyOverridden(), "dbeaver.mcp.authToken", "DBEAVER_MCP_AUTH_TOKEN");
      return result.toString();
   }

   private static void appendOverride(StringBuilder result, boolean overridden, String property, String environment) {
      if (overridden) {
         if (!result.isEmpty()) {
            result.append(", ");
         }

         result.append(System.getProperty(property) != null ? "-D" + property : environment);
      }
   }

   private static boolean isExternallyOverridden(String property, String environment) {
      return System.getProperty(property) != null || System.getenv(environment) != null;
   }

   private static boolean readBoolean(String property, String environment, boolean defaultValue) {
      String value = readExternal(property, environment);
      return value == null ? defaultValue : !"false".equalsIgnoreCase(value.trim());
   }

   private static int readInt(String property, String environment, int defaultValue, int minimum, int maximum) {
      String value = readExternal(property, environment);
      if (value == null) {
         return defaultValue >= minimum && defaultValue <= maximum ? defaultValue : 3846;
      } else {
         try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= minimum && parsed <= maximum ? parsed : defaultValue;
         } catch (NumberFormatException var7) {
            return defaultValue;
         }
      }
   }

   private static String readString(String property, String environment, String defaultValue) {
      String value = readExternal(property, environment);
      return value == null ? defaultValue : value;
   }

   private static String readExternal(String property, String environment) {
      String propertyValue = System.getProperty(property);
      return propertyValue != null ? propertyValue : System.getenv(environment);
   }

   private static int validPort(int port) {
      return port >= 1 && port <= 65535 ? port : 3846;
   }

   private static String valueOrEmpty(String value) {
      return value == null ? "" : value;
   }

   private static DBPPreferenceStore store() {
      return DBWorkbench.getPlatform().getPreferenceStore();
   }

   record Config(boolean autoStart, int port, String authToken) {
   }
}
