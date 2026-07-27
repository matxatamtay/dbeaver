/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.model.task.DBTTaskExecutionListener;
import org.jkiss.dbeaver.model.task.DBTTaskManager;
import org.jkiss.dbeaver.model.task.DBTTaskRun;
import org.jkiss.dbeaver.model.task.DBTTaskScheduleConfiguration;
import org.jkiss.dbeaver.model.task.DBTTaskScheduleInfo;
import org.jkiss.dbeaver.model.task.DBTTaskType;
import org.jkiss.dbeaver.registry.task.TaskRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;

final class DBeaverTaskService {
   JsonObject execute(String action, JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      return switch (action) {
         case "list_types" -> listTypes(arguments);
         case "list" -> listTasks(arguments);
         case "describe" -> describe(arguments);
         case "create" -> create(arguments);
         case "update" -> update(arguments);
         case "delete" -> delete(arguments);
         case "run" -> run(arguments, jobs);
         case "cancel_running" -> cancelRunning(arguments);
         case "history" -> history(arguments);
         case "read_log" -> readLog(arguments);
         case "schedule" -> schedule(arguments);
         case "unschedule" -> unschedule(arguments);
         case "scheduled" -> scheduled(arguments);
         default -> throw new IllegalArgumentException("Unknown task action: " + action);
      };
   }

   private JsonObject listTypes(JsonObject arguments) {
      DBTTaskManager manager = manager(arguments, false);
      DBTTaskType[] types = manager == null ? TaskRegistry.getInstance().getAllTaskTypes() : manager.getRegistry().getAllTaskTypes();
      JsonArray items = new JsonArray();
      for (DBTTaskType type : types) {
         JsonObject item = new JsonObject();
         item.addProperty("id", type.getId());
         item.addProperty("name", type.getName());
         item.addProperty("description", type.getDescription());
         item.addProperty("standalone", type.isStandalone());
         item.addProperty("supports_variables", type.supportsVariables());
         JsonArray properties = new JsonArray();
         Arrays.stream(type.getConfigurationProperties()).forEach(property -> {
            JsonObject p = new JsonObject();
            p.addProperty("id", property.getId());
            p.addProperty("name", property.getDisplayName());
            p.addProperty("description", property.getDescription());
            properties.add(p);
         });
         item.add("configuration_properties", properties);
         items.add(item);
      }
      return listPayload("types", items);
   }

   private JsonObject listTasks(JsonObject arguments) {
      JsonArray items = new JsonArray();
      for (DBPProject project : projects(arguments)) {
         DBTTaskManager manager = project.getTaskManager(false);
         if (manager == null) continue;
         for (DBTTask task : manager.getAllTasks()) items.add(taskPayload(task, false));
      }
      return listPayload("tasks", items);
   }

   private JsonObject describe(JsonObject arguments) {
      DBTTask task = task(arguments);
      JsonObject result = taskPayload(task, true);
      result.add("runs", runsPayload(task, 20));
      return result;
   }

   private JsonObject create(JsonObject arguments) throws Exception {
      requireConfirm(arguments, "Create DBeaver task?", "Create task '" + McpJson.requiredString(arguments, "name") + "' in project " + project(arguments).getName() + "?");
      DBTTaskManager manager = manager(arguments, true);
      String typeId = McpJson.requiredString(arguments, "type_id");
      DBTTaskType type = manager.getRegistry().getTaskType(typeId);
      if (type == null) throw new IllegalArgumentException("DBeaver task type not found: " + typeId);
      DBTTask task = manager.createTask(type, McpJson.requiredString(arguments, "name"),
         blankToNull(McpJson.getString(arguments, "description", "")), blankToNull(McpJson.getString(arguments, "folder", "")), map(arguments, "properties"));
      manager.updateTaskConfiguration(task);
      JsonObject result = taskPayload(task, true);
      result.addProperty("created", true);
      return result;
   }

   private JsonObject update(JsonObject arguments) throws Exception {
      DBTTask task = task(arguments);
      requireConfirm(arguments, "Update DBeaver task?", "Update configuration for task '" + task.getName() + "'?");
      Map<String, Object> properties = map(arguments, "properties");
      if (properties.isEmpty()) throw new IllegalArgumentException("properties must contain at least one value");
      Map<String, Object> merged = new LinkedHashMap<>(task.getProperties());
      merged.putAll(properties);
      task.setProperties(merged);
      task.getProject().getTaskManager().updateTaskConfiguration(task);
      JsonObject result = taskPayload(task, true);
      result.addProperty("updated", true);
      return result;
   }

   private JsonObject delete(JsonObject arguments) throws Exception {
      DBTTask task = task(arguments);
      requireConfirm(arguments, "Delete DBeaver task?", "Permanently delete task '" + task.getName() + "' and its schedule?");
      task.getProject().getTaskManager().deleteTaskConfiguration(task);
      JsonObject result = new JsonObject();
      result.addProperty("deleted", true);
      result.addProperty("task_id", task.getId());
      return result;
   }

   private JsonObject run(JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      DBTTask task = task(arguments);
      requireConfirm(arguments, "Run DBeaver task?", "Run task '" + task.getName() + "' now? It may modify databases or files according to its configuration.");
      String jobId = jobs.submit("desktop-workflows", "run-task", true, context -> {
         context.checkCancelled();
         var status = task.getProject().getTaskManager().runTask(new VoidProgressMonitor(), task, NOOP_LISTENER);
         context.checkCancelled();
         JsonObject result = taskPayload(task, false);
         result.addProperty("run_status", String.valueOf(status));
         result.addProperty("finished", true);
         return result;
      });
      return jobPayload(jobId, "run_task");
   }

   private JsonObject cancelRunning(JsonObject arguments) throws Exception {
      DBTTaskManager manager = manager(arguments, true);
      requireConfirm(arguments, "Cancel running DBeaver tasks?", "Cancel all running tasks in project '" + manager.getProject().getName() + "'?");
      boolean running = manager.hasRunningTasks();
      manager.cancelRunningTasks();
      JsonObject result = new JsonObject();
      result.addProperty("had_running_tasks", running);
      result.addProperty("cancel_requested", true);
      return result;
   }

   private JsonObject history(JsonObject arguments) {
      DBTTask task = task(arguments);
      int limit = McpJson.getInt(arguments, "limit", 20, 1, 100);
      JsonObject result = taskPayload(task, false);
      result.add("runs", runsPayload(task, limit));
      return result;
   }

   private JsonObject readLog(JsonObject arguments) throws Exception {
      DBTTask task = task(arguments);
      String runId = McpJson.requiredString(arguments, "run_id");
      int maxChars = McpJson.getInt(arguments, "max_chars", 32768, 1, 65536);
      DBTTaskRun run = Arrays.stream(task.getAllRuns()).filter(item -> item.getId().equals(runId)).findFirst()
         .orElseThrow(() -> new IllegalArgumentException("Task run not found: " + runId));
      try (InputStream input = task.getRunLogInputStream(run)) {
         byte[] bytes = input.readNBytes(maxChars + 1);
         boolean truncated = bytes.length > maxChars;
         String text = new String(bytes, 0, Math.min(bytes.length, maxChars), StandardCharsets.UTF_8);
         JsonObject result = runPayload(run);
         result.addProperty("log", text);
         result.addProperty("truncated", truncated);
         return result;
      }
   }

   private JsonObject schedule(JsonObject arguments) throws Exception {
      DBTTask task = task(arguments);
      var scheduler = TaskRegistry.getInstance().getActiveSchedulerInstance();
      if (scheduler == null) throw new IllegalStateException("No active DBeaver task scheduler is available");
      DBTTaskScheduleConfiguration configuration = scheduleConfiguration(arguments);
      requireConfirm(arguments, "Schedule DBeaver task?", "Schedule task '" + task.getName() + "' with frequency " + configuration.frequency + "?");
      boolean scheduled = scheduler.setTaskSchedule(task, configuration);
      JsonObject result = schedulePayload(scheduler.getScheduledTaskInfo(task));
      result.addProperty("scheduled", scheduled);
      return result;
   }

   private JsonObject unschedule(JsonObject arguments) throws Exception {
      DBTTask task = task(arguments);
      var scheduler = TaskRegistry.getInstance().getActiveSchedulerInstance();
      if (scheduler == null) throw new IllegalStateException("No active DBeaver task scheduler is available");
      DBTTaskScheduleInfo info = scheduler.getScheduledTaskInfo(task);
      if (info == null) throw new IllegalStateException("Task is not scheduled: " + task.getName());
      requireConfirm(arguments, "Remove DBeaver task schedule?", "Remove the schedule for task '" + task.getName() + "'?");
      scheduler.removeTaskSchedule(task, info);
      JsonObject result = new JsonObject();
      result.addProperty("unscheduled", true);
      result.addProperty("task_id", task.getId());
      return result;
   }

   private JsonObject scheduled(JsonObject arguments) throws Exception {
      var scheduler = TaskRegistry.getInstance().getActiveSchedulerInstance();
      JsonArray items = new JsonArray();
      if (scheduler != null) {
         String project = McpJson.getString(arguments, "project", "");
         for (DBTTaskScheduleInfo info : scheduler.getAllScheduledTasks()) {
            if (project.isBlank() || project.equals(info.getProjectId())) items.add(schedulePayload(info));
         }
      }
      JsonObject result = listPayload("scheduled_tasks", items);
      result.addProperty("scheduler", scheduler == null ? "unavailable" : scheduler.getSchedulerName());
      return result;
   }

   private static DBTTaskScheduleConfiguration scheduleConfiguration(JsonObject arguments) {
      DBTTaskScheduleConfiguration config = new DBTTaskScheduleConfiguration();
      config.frequency = DBTTaskScheduleConfiguration.Frequency.valueOf(McpJson.requiredString(arguments, "frequency").toUpperCase(Locale.ENGLISH));
      String start = McpJson.getString(arguments, "start_time", "");
      if (!start.isBlank()) config.startTime = Date.from(Instant.parse(start));
      String end = McpJson.getString(arguments, "end_time", "");
      if (!end.isBlank()) config.endTime = Date.from(Instant.parse(end));
      config.recurrence = McpJson.getInt(arguments, "recurrence", 1, 1, 100000);
      config.repetitionInterval = McpJson.getInt(arguments, "repetition_interval", 0, 0, 100000);
      config.executionMinute = McpJson.getInt(arguments, "execution_minute", 0, 0, 59);
      config.maxDuration = McpJson.getInt(arguments, "max_duration_seconds", 0, 0, 8640000);
      config.days = shorts(arguments, "days");
      config.months = shorts(arguments, "months");
      config.properties.putAll(map(arguments, "properties"));
      return config;
   }

   private static List<Short> shorts(JsonObject arguments, String name) {
      JsonElement value = arguments.get(name);
      if (value == null || !value.isJsonArray()) return List.of();
      List<Short> result = new ArrayList<>();
      value.getAsJsonArray().forEach(item -> result.add(item.getAsShort()));
      return List.copyOf(result);
   }

   private static JsonObject taskPayload(DBTTask task, boolean includeProperties) {
      JsonObject result = new JsonObject();
      result.addProperty("project", task.getProject().getName());
      result.addProperty("id", task.getId());
      result.addProperty("name", task.getName());
      result.addProperty("description", task.getDescription());
      result.addProperty("type_id", task.getType().getId());
      result.addProperty("type", task.getType().getName());
      result.addProperty("temporary", task.isTemporary());
      result.addProperty("created_at", task.getCreateTime().toInstant().toString());
      result.addProperty("updated_at", task.getUpdateTime().toInstant().toString());
      if (task.getTaskFolder() != null) result.addProperty("folder", task.getTaskFolder().getName());
      if (includeProperties) result.add("properties", McpJson.GSON.toJsonTree(task.getProperties()));
      DBTTaskRun last = task.getLastRun();
      if (last != null) result.add("last_run", runPayload(last));
      return result;
   }

   private static JsonArray runsPayload(DBTTask task, int limit) {
      JsonArray result = new JsonArray();
      DBTTaskRun[] runs = task.getAllRuns();
      for (int i = Math.max(0, runs.length - limit); i < runs.length; i++) result.add(runPayload(runs[i]));
      return result;
   }

   private static JsonObject runPayload(DBTTaskRun run) {
      JsonObject result = new JsonObject();
      result.addProperty("id", run.getId());
      result.addProperty("started_at", run.getStartTime().toInstant().toString());
      result.addProperty("started_by", run.getStartedBy());
      result.addProperty("start_user", run.getStartUser());
      result.addProperty("duration_ms", run.getRunDuration());
      result.addProperty("finished", run.isFinished());
      result.addProperty("success", run.isRunSuccess());
      if (run.getErrorMessage() != null) result.addProperty("error", McpJson.truncate(run.getErrorMessage()));
      if (run.getExtraMessage() != null) result.addProperty("message", McpJson.truncate(run.getExtraMessage()));
      return result;
   }

   private static JsonObject schedulePayload(DBTTaskScheduleInfo info) {
      JsonObject result = new JsonObject();
      if (info == null) return result;
      result.addProperty("project_id", info.getProjectId());
      result.addProperty("task_id", info.getTaskId());
      result.addProperty("status", info.getStatus());
      result.addProperty("next_run", info.getNextRunInfo());
      return result;
   }

   private static JsonObject listPayload(String name, JsonArray items) {
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.add(name, items);
      return result;
   }

   private static JsonObject jobPayload(String jobId, String type) {
      JsonObject result = new JsonObject();
      result.addProperty("job_id", jobId);
      result.addProperty("type", type);
      result.addProperty("state", "queued");
      result.addProperty("status_tool", "dbeaver_job");
      return result;
   }

   private static Map<String, Object> map(JsonObject arguments, String name) {
      JsonObject value = McpJson.getObject(arguments, name);
      return McpJson.GSON.fromJson(value, Map.class);
   }

   private static void requireConfirm(JsonObject arguments, String title, String message) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      if (!DBeaverNativeConfirmation.confirm(title, message)) throw new IllegalStateException("Operation cancelled by the DBeaver user");
   }

   private static DBPProject project(JsonObject arguments) {
      String name = McpJson.requiredString(arguments, "project");
      DBPProject project = DBWorkbench.getPlatform().getWorkspace().getProject(name);
      if (project == null) throw new IllegalArgumentException("DBeaver project not found: " + name);
      return project;
   }

   private static DBTTaskManager manager(JsonObject arguments, boolean required) {
      String projectName = McpJson.getString(arguments, "project", "");
      DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
      DBPProject project = projectName.isBlank() ? workspace.getActiveProject() : workspace.getProject(projectName);
      if (project == null) {
         if (required) throw new IllegalStateException("No DBeaver project is selected");
         return null;
      }
      DBTTaskManager manager = project.getTaskManager(required);
      if (manager == null && required) throw new IllegalStateException("Task manager is unavailable for project: " + project.getName());
      return manager;
   }

   private static List<? extends DBPProject> projects(JsonObject arguments) {
      String projectName = McpJson.getString(arguments, "project", "");
      if (projectName.isBlank()) return DBWorkbench.getPlatform().getWorkspace().getProjects();
      return List.of(project(arguments));
   }

   private static DBTTask task(JsonObject arguments) {
      DBTTaskManager manager = manager(arguments, true);
      String id = McpJson.getString(arguments, "task_id", "");
      String name = McpJson.getString(arguments, "name", "");
      DBTTask task = !id.isBlank() ? manager.getTaskById(id) : !name.isBlank() ? manager.getTaskByName(name) : null;
      if (task == null) throw new IllegalArgumentException("DBeaver task not found; pass task_id or name");
      return task;
   }

   private static String blankToNull(String value) {
      return value == null || value.isBlank() ? null : value;
   }

   private static final DBTTaskExecutionListener NOOP_LISTENER = new DBTTaskExecutionListener() {
      @Override public void taskStarted(DBTTask task) { }
      @Override public void taskFinished(DBTTask task, Object result, Throwable error, Object settings) { }
      @Override public void subTaskFinished(DBTTask task, Throwable error, Object settings) { }
   };
}
