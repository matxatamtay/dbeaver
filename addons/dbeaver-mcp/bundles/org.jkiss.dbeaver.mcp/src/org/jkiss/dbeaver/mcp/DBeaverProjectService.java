/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;

final class DBeaverProjectService {
   private static final int MAX_SCRIPT_BYTES = 1024 * 1024;

   JsonObject execute(String action, JsonObject arguments) throws Exception {
      return switch (action) {
         case "list" -> list();
         case "create" -> create(arguments);
         case "rename" -> rename(arguments);
         case "delete" -> delete(arguments);
         case "refresh" -> refresh(arguments);
         case "list_scripts" -> listScripts(arguments);
         case "read_script" -> readScript(arguments);
         case "write_script" -> writeScript(arguments);
         case "delete_script" -> deleteScript(arguments);
         default -> throw new IllegalArgumentException("Unknown project action: " + action);
      };
   }

   private JsonObject list() {
      DBPWorkspace workspace = workspace();
      JsonArray items = new JsonArray();
      for (DBPProject project : workspace.getProjects()) items.add(projectPayload(project, workspace.getActiveProject() == project));
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.addProperty("workspace", workspace.getAbsolutePath().toString());
      result.add("projects", items);
      return result;
   }

   private JsonObject create(JsonObject arguments) throws Exception {
      String name = safeName(McpJson.requiredString(arguments, "name"));
      requireConfirm(arguments, "Create DBeaver project?", "Create project '" + name + "' in this DBeaver workspace?");
      DBPWorkspace workspace = workspace();
      if (!workspace.canManageProjects()) throw new IllegalStateException("Current DBeaver user cannot manage projects");
      if (workspace.getProject(name) != null) throw new IllegalArgumentException("Project already exists: " + name);
      DBPProject project = workspace.createProject(name, blankToNull(McpJson.getString(arguments, "description", "")));
      JsonObject result = projectPayload(project, workspace.getActiveProject() == project);
      result.addProperty("created", true);
      return result;
   }

   private JsonObject rename(JsonObject arguments) throws Exception {
      DBPProject project = project(arguments);
      String newName = safeName(McpJson.requiredString(arguments, "new_name"));
      requireConfirm(arguments, "Rename DBeaver project?", "Rename project '" + project.getName() + "' to '" + newName + "'?");
      workspace().renameProject(project, newName);
      JsonObject result = projectPayload(project, workspace().getActiveProject() == project);
      result.addProperty("renamed", true);
      return result;
   }

   private JsonObject delete(JsonObject arguments) throws Exception {
      DBPProject project = project(arguments);
      if (!McpJson.getBoolean(arguments, "acknowledge_delete", false)) {
         throw new IllegalArgumentException("acknowledge_delete=true is required because project deletion removes project resources");
      }
      requireConfirm(arguments, "Delete DBeaver project?", "Permanently delete project '" + project.getName() + "' and its local resources?");
      workspace().deleteProject(project);
      JsonObject result = new JsonObject();
      result.addProperty("deleted", true);
      result.addProperty("project", project.getName());
      return result;
   }

   private JsonObject refresh(JsonObject arguments) {
      DBPProject project = project(arguments);
      project.refreshProject(new VoidProgressMonitor());
      JsonObject result = projectPayload(project, workspace().getActiveProject() == project);
      result.addProperty("refreshed", true);
      return result;
   }

   private JsonObject listScripts(JsonObject arguments) throws IOException {
      DBPProject project = project(arguments);
      int limit = McpJson.getInt(arguments, "limit", 200, 1, 500);
      Path root = DBeaverProjectPathPolicy.scriptsRoot(project.getAbsolutePath(), true);
      Path requested = DBeaverProjectPathPolicy.resolve(root, McpJson.getString(arguments, "path", ""), false);
      JsonArray items = new JsonArray();
      if (Files.exists(requested)) {
         try (Stream<Path> paths = Files.walk(requested, 12)) {
            paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".sql"))
               .sorted(Comparator.comparing(Path::toString)).limit(limit).forEach(path -> {
                  JsonObject item = new JsonObject();
                  item.addProperty("path", root.relativize(path).toString());
                  try {
                     item.addProperty("bytes", Files.size(path));
                     item.addProperty("modified_at", Files.getLastModifiedTime(path).toInstant().toString());
                  } catch (IOException e) {
                     item.addProperty("error", McpJson.safeMessage(e));
                  }
                  items.add(item);
               });
         }
      }
      JsonObject result = new JsonObject();
      result.addProperty("project", project.getName());
      result.addProperty("root", root.toString());
      result.addProperty("count", items.size());
      result.add("scripts", items);
      return result;
   }

   private JsonObject readScript(JsonObject arguments) throws IOException {
      DBPProject project = project(arguments);
      int maxChars = McpJson.getInt(arguments, "max_chars", 262144, 1, MAX_SCRIPT_BYTES);
      Path root = DBeaverProjectPathPolicy.scriptsRoot(project.getAbsolutePath(), false);
      Path file = DBeaverProjectPathPolicy.resolve(root, McpJson.requiredString(arguments, "path"), true);
      if (!Files.isRegularFile(file)) throw new IllegalArgumentException("SQL script not found: " + file);
      long bytes = Files.size(file);
      if (bytes > MAX_SCRIPT_BYTES) throw new IllegalArgumentException("SQL script exceeds the 1 MiB safety limit: " + bytes);
      String content = Files.readString(file, StandardCharsets.UTF_8);
      boolean truncated = content.length() > maxChars;
      JsonObject result = new JsonObject();
      result.addProperty("project", project.getName());
      result.addProperty("path", root.relativize(file).toString());
      result.addProperty("bytes", bytes);
      result.addProperty("content", truncated ? content.substring(0, maxChars) : content);
      result.addProperty("truncated", truncated);
      return result;
   }

   private JsonObject writeScript(JsonObject arguments) throws Exception {
      DBPProject project = project(arguments);
      Path root = DBeaverProjectPathPolicy.scriptsRoot(project.getAbsolutePath(), true);
      Path file = DBeaverProjectPathPolicy.resolve(root, McpJson.requiredString(arguments, "path"), false);
      if (!file.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".sql")) throw new IllegalArgumentException("Script path must end with .sql");
      String content = McpJson.getString(arguments, "content", "");
      int bytes = content.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_SCRIPT_BYTES) throw new IllegalArgumentException("SQL script exceeds the 1 MiB safety limit");
      boolean overwrite = McpJson.getBoolean(arguments, "overwrite", false);
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !overwrite) throw new IllegalArgumentException("Script exists; pass overwrite=true: " + file);
      requireConfirm(arguments, "Write DBeaver SQL script?", "Write " + bytes + " bytes to project script '" + root.relativize(file) + "'?");
      Files.createDirectories(file.getParent());
      DBeaverProjectPathPolicy.resolve(root, root.relativize(file).toString(), false);
      Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      JsonObject result = new JsonObject();
      result.addProperty("written", true);
      result.addProperty("project", project.getName());
      result.addProperty("path", root.relativize(file).toString());
      result.addProperty("bytes", bytes);
      return result;
   }

   private JsonObject deleteScript(JsonObject arguments) throws Exception {
      DBPProject project = project(arguments);
      Path root = DBeaverProjectPathPolicy.scriptsRoot(project.getAbsolutePath(), false);
      Path file = DBeaverProjectPathPolicy.resolve(root, McpJson.requiredString(arguments, "path"), true);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("SQL script not found: " + file);
      requireConfirm(arguments, "Delete DBeaver SQL script?", "Permanently delete project script '" + root.relativize(file) + "'?");
      Files.delete(file);
      JsonObject result = new JsonObject();
      result.addProperty("deleted", true);
      result.addProperty("path", root.relativize(file).toString());
      return result;
   }

   private static JsonObject projectPayload(DBPProject project, boolean active) {
      JsonObject result = new JsonObject();
      result.addProperty("id", project.getId());
      result.addProperty("name", project.getName());
      result.addProperty("display_name", project.getDisplayName());
      result.addProperty("description", project.getDescription());
      result.addProperty("path", project.getAbsolutePath().toString());
      result.addProperty("active", active);
      result.addProperty("open", project.isOpen());
      result.addProperty("private", project.isPrivateProject());
      result.addProperty("encrypted", project.isEncryptedProject());
      result.addProperty("in_memory", project.isInMemory());
      return result;
   }

   private static DBPWorkspace workspace() {
      return DBWorkbench.getPlatform().getWorkspace();
   }

   private static DBPProject project(JsonObject arguments) {
      String name = McpJson.requiredString(arguments, "project");
      DBPProject project = workspace().getProject(name);
      if (project == null) throw new IllegalArgumentException("DBeaver project not found: " + name);
      return project;
   }

   private static void requireConfirm(JsonObject arguments, String title, String message) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      if (!DBeaverNativeConfirmation.confirm(title, message)) throw new IllegalStateException("Operation cancelled by the DBeaver user");
   }

   private static String safeName(String value) {
      if (!value.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}")) throw new IllegalArgumentException("Project name contains unsupported characters");
      return value;
   }

   private static String blankToNull(String value) {
      return value == null || value.isBlank() ? null : value;
   }
}
