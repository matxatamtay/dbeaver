/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverLibrary;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;

final class DBeaverEnvironmentService {
   JsonObject execute(String action, JsonObject arguments) throws Exception {
      return switch (action) {
         case "list_drivers" -> listDrivers(arguments);
         case "describe_driver" -> describeDriver(arguments);
         case "validate_connection_driver" -> validateConnectionDriver(arguments);
         case "get_preference" -> getPreference(arguments);
         case "set_preference" -> setPreference(arguments);
         case "reset_preference" -> resetPreference(arguments);
         default -> throw new IllegalArgumentException("Unknown environment action: " + action);
      };
   }

   private JsonObject listDrivers(JsonObject arguments) {
      String search = McpJson.getString(arguments, "search", "").toLowerCase(Locale.ENGLISH);
      String provider = McpJson.getString(arguments, "provider", "");
      int limit = McpJson.getInt(arguments, "limit", 200, 1, 500);
      List<DBPDriver> drivers = new ArrayList<>();
      DataSourceProviderRegistry.getInstance().getDataSourceProviders().forEach(descriptor -> {
         if (provider.isBlank() || descriptor.getId().equals(provider)) drivers.addAll(descriptor.getDrivers());
      });
      JsonArray items = new JsonArray();
      drivers.stream().filter(driver -> search.isBlank() || (driver.getFullId() + " " + driver.getName()).toLowerCase(Locale.ENGLISH).contains(search))
         .sorted(Comparator.comparing(DBPDriver::getFullId)).limit(limit).forEach(driver -> items.add(driverPayload(driver, false)));
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.add("drivers", items);
      return result;
   }

   private JsonObject describeDriver(JsonObject arguments) {
      DBPDriver driver = driver(McpJson.requiredString(arguments, "driver"));
      return driverPayload(driver, true);
   }

   private JsonObject validateConnectionDriver(JsonObject arguments) throws Exception {
      DBPDataSourceContainer container = DBeaverConnectionService.findConnection(
         McpJson.requiredString(arguments, "connection"), McpJson.getString(arguments, "project", ""));
      DBPDriver driver = container.getDriver();
      JsonObject result = driverPayload(driver, true);
      try {
         driver.validateFilesPresence(new VoidProgressMonitor(), container);
         result.addProperty("valid", true);
      } catch (Exception e) {
         result.addProperty("valid", false);
         result.addProperty("error", McpJson.safeMessage(e));
      }
      result.add("connection", DBeaverConnectionService.connectionPayload(container));
      return result;
   }

   private JsonObject getPreference(JsonObject arguments) {
      String key = key(arguments);
      DBeaverPreferencePolicy.requireSafeKey(key);
      DBPPreferenceStore store = store();
      JsonObject result = new JsonObject();
      result.addProperty("key", key);
      result.addProperty("contains", store.contains(key));
      result.addProperty("is_default", store.isDefault(key));
      result.addProperty("value", store.getString(key));
      result.addProperty("default_value", store.getDefaultString(key));
      return result;
   }

   private JsonObject setPreference(JsonObject arguments) throws Exception {
      String key = key(arguments);
      DBeaverPreferencePolicy.requireSafeKey(key);
      JsonElement value = arguments.get("value");
      if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("value must be a string, boolean, or number");
      DBPPreferenceStore store = store();
      String oldValue = store.getString(key);
      requireConfirm(arguments, "Change DBeaver preference?", "Change preference '" + key + "' from '" + oldValue + "' to '" + McpJson.truncate(value.getAsString()) + "'?");
      if (value.getAsJsonPrimitive().isBoolean()) store.setValue(key, value.getAsBoolean());
      else if (value.getAsJsonPrimitive().isNumber()) {
         String raw = value.getAsString();
         if (raw.contains(".") || raw.contains("e") || raw.contains("E")) store.setValue(key, value.getAsDouble());
         else store.setValue(key, value.getAsLong());
      } else store.setValue(key, value.getAsString());
      store.save();
      JsonObject result = getPreference(arguments);
      result.addProperty("updated", true);
      return result;
   }

   private JsonObject resetPreference(JsonObject arguments) throws Exception {
      String key = key(arguments);
      DBeaverPreferencePolicy.requireSafeKey(key);
      DBPPreferenceStore store = store();
      requireConfirm(arguments, "Reset DBeaver preference?", "Reset preference '" + key + "' to its default value?");
      store.setToDefault(key);
      store.save();
      JsonObject result = getPreference(arguments);
      result.addProperty("reset", true);
      return result;
   }

   private static JsonObject driverPayload(DBPDriver driver, boolean details) {
      JsonObject result = new JsonObject();
      result.addProperty("id", driver.getId());
      result.addProperty("full_id", driver.getFullId());
      result.addProperty("provider_id", driver.getProviderId());
      result.addProperty("name", driver.getName());
      result.addProperty("description", driver.getDescription());
      result.addProperty("driver_class", driver.getDriverClassName());
      result.addProperty("embedded", driver.isEmbedded());
      result.addProperty("internal", driver.isInternalDriver());
      result.addProperty("custom", driver.isCustom());
      result.addProperty("disabled", driver.isDisabled());
      result.addProperty("instantiable", driver.isInstantiable());
      result.addProperty("supported_on_local_system", driver.isSupportedByLocalSystem());
      result.addProperty("default_host", driver.getDefaultHost());
      result.addProperty("default_port", driver.getDefaultPort());
      result.addProperty("default_database", driver.getDefaultDatabase());
      result.addProperty("sample_url", driver.getSampleURL());
      if (details) {
         JsonArray categories = new JsonArray();
         driver.getCategories().forEach(categories::add);
         result.add("categories", categories);
         JsonArray libraries = new JsonArray();
         for (DBPDriverLibrary library : driver.getDriverLibraries()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", library.getId());
            item.addProperty("name", library.getDisplayName());
            item.addProperty("version", library.getVersion());
            item.addProperty("type", library.getType().name());
            item.addProperty("optional", library.isOptional());
            item.addProperty("embedded", library.isEmbedded());
            item.addProperty("disabled", library.isDisabled());
            item.addProperty("downloadable", library.isDownloadable());
            item.addProperty("invalid", library.isInvalidLibrary());
            item.addProperty("matches_platform", library.matchesCurrentPlatform());
            if (library.getLocalFile() != null) {
               item.addProperty("installed", Files.exists(library.getLocalFile()));
               item.addProperty("local_file", library.getLocalFile().toString());
            }
            libraries.add(item);
         }
         result.add("libraries", libraries);
         result.add("default_connection_properties", McpJson.GSON.toJsonTree(driver.getDefaultConnectionProperties()));
         result.add("driver_parameters", McpJson.GSON.toJsonTree(driver.getDriverParameters()));
      }
      return result;
   }

   private static DBPDriver driver(String idOrName) {
      DBPDriver driver = DataSourceProviderRegistry.getInstance().findDriver(idOrName);
      if (driver == null) throw new IllegalArgumentException("DBeaver driver not found: " + idOrName);
      return driver;
   }

   private static DBPPreferenceStore store() {
      return DBWorkbench.getPlatform().getPreferenceStore();
   }

   private static String key(JsonObject arguments) {
      return DBeaverPreferencePolicy.requireSafeKey(McpJson.requiredString(arguments, "key"));
   }

   private static void requireConfirm(JsonObject arguments, String title, String message) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      if (!DBeaverNativeConfirmation.confirm(title, message)) throw new IllegalStateException("Operation cancelled by the DBeaver user");
   }
}
