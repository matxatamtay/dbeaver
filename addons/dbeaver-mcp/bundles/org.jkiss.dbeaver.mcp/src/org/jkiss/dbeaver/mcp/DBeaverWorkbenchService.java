/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IParameter;
import org.eclipse.core.commands.Parameterization;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.notifications.NotificationSettings;
import org.jkiss.dbeaver.ui.notifications.NotificationUtils;
import org.jkiss.dbeaver.ui.registry.NotificationDescriptor;
import org.jkiss.dbeaver.ui.registry.NotificationRegistry;

final class DBeaverWorkbenchService {
   private static final Set<String> SAFE_COMMANDS = Set.of(
      "org.eclipse.ui.edit.copy",
      "org.eclipse.ui.edit.selectAll",
      "org.eclipse.ui.edit.findReplace",
      "org.eclipse.ui.navigate.next",
      "org.eclipse.ui.navigate.previous",
      "org.eclipse.ui.window.nextEditor",
      "org.eclipse.ui.window.previousEditor",
      "org.eclipse.ui.window.nextView",
      "org.eclipse.ui.window.previousView"
   );
   private static final List<String> SAFE_COMMAND_PREFIXES = List.of(
      "org.eclipse.ui.navigate.",
      "org.eclipse.ui.window.next",
      "org.eclipse.ui.window.previous"
   );

   JsonObject execute(String action, JsonObject arguments) throws Exception {
      return switch (action) {
         case "state" -> state();
         case "list_editors" -> listEditors();
         case "list_views" -> listViews();
         case "list_perspectives" -> listPerspectives();
         case "activate_part" -> activatePart(arguments);
         case "save_editor" -> saveEditor(arguments);
         case "close_editor" -> closeEditor(arguments);
         case "open_view" -> openView(arguments);
         case "hide_view" -> hideView(arguments);
         case "switch_perspective" -> switchPerspective(arguments);
         case "list_commands" -> listCommands(arguments);
         case "execute_command" -> executeCommand(arguments);
         case "list_jobs" -> listJobs(arguments);
         case "cancel_job" -> cancelJob(arguments);
         case "list_notification_types" -> listNotificationTypes(arguments);
         case "get_notification_settings" -> getNotificationSettings(arguments);
         case "set_notification_settings" -> setNotificationSettings(arguments);
         case "send_test_notification" -> sendTestNotification(arguments);
         default -> throw new IllegalArgumentException("Unknown workbench action: " + action);
      };
   }

   private JsonObject state() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchWindow window = activeWindow();
         IWorkbenchPage page = activePage(window);
         JsonObject result = new JsonObject();
         result.addProperty("window_count", PlatformUI.getWorkbench().getWorkbenchWindowCount());
         result.addProperty("editor_count", page.getEditorReferences().length);
         result.addProperty("view_count", page.getViewReferences().length);
         if (page.getPerspective() != null) {
            result.addProperty("perspective_id", page.getPerspective().getId());
            result.addProperty("perspective", page.getPerspective().getLabel());
         }
         IWorkbenchPartReference active = page.getActivePartReference();
         if (active != null) result.add("active_part", partPayload(active, true));
         return result;
      });
   }

   private JsonObject listEditors() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchPage page = activePage(activeWindow());
         IEditorPart activeEditor = page.getActiveEditor();
         JsonArray items = new JsonArray();
         for (IEditorReference reference : page.getEditorReferences()) {
            JsonObject item = partPayload(reference, reference.getEditor(false) == activeEditor);
            item.addProperty("kind", "editor");
            item.addProperty("dirty", reference.isDirty());
            items.add(item);
         }
         JsonObject result = new JsonObject();
         result.addProperty("count", items.size());
         result.add("editors", items);
         return result;
      });
   }

   private JsonObject listViews() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchPage page = activePage(activeWindow());
         IWorkbenchPartReference active = page.getActivePartReference();
         JsonArray items = new JsonArray();
         for (IViewReference reference : page.getViewReferences()) {
            JsonObject item = partPayload(reference, reference == active);
            item.addProperty("kind", "view");
            if (reference.getSecondaryId() != null) item.addProperty("secondary_id", reference.getSecondaryId());
            items.add(item);
         }
         JsonObject result = new JsonObject();
         result.addProperty("count", items.size());
         result.add("views", items);
         return result;
      });
   }

   private JsonObject listPerspectives() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IWorkbench workbench = PlatformUI.getWorkbench();
         IWorkbenchPage page = activePage(activeWindow());
         String activeId = page.getPerspective() == null ? "" : page.getPerspective().getId();
         JsonArray items = new JsonArray();
         for (IPerspectiveDescriptor descriptor : workbench.getPerspectiveRegistry().getPerspectives()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", descriptor.getId());
            item.addProperty("label", descriptor.getLabel());
            item.addProperty("description", descriptor.getDescription());
            item.addProperty("active", descriptor.getId().equals(activeId));
            items.add(item);
         }
         JsonObject result = new JsonObject();
         result.addProperty("count", items.size());
         result.add("perspectives", items);
         return result;
      });
   }

   private JsonObject activatePart(JsonObject arguments) throws Exception {
      String partId = McpJson.requiredString(arguments, "part_id");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchPage page = activePage(activeWindow());
         IWorkbenchPartReference reference = resolvePart(page, partId);
         if (reference.getPart(false) == null) reference.getPart(true);
         page.activate(reference.getPart(false));
         JsonObject result = partPayload(reference, true);
         result.addProperty("activated", true);
         return result;
      });
   }

   private JsonObject saveEditor(JsonObject arguments) throws Exception {
      requireConfirm(arguments, "Save DBeaver editor?", "Save the selected editor and persist all of its current changes?");
      String partId = McpJson.requiredString(arguments, "part_id");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchPage page = activePage(activeWindow());
         IEditorReference reference = resolveEditor(page, partId);
         IEditorPart editor = reference.getEditor(true);
         boolean saved = page.saveEditor(editor, false);
         JsonObject result = partPayload(reference, page.getActiveEditor() == editor);
         result.addProperty("saved", saved);
         result.addProperty("dirty", reference.isDirty());
         return result;
      });
   }

   private JsonObject closeEditor(JsonObject arguments) throws Exception {
      String partId = McpJson.requiredString(arguments, "part_id");
      boolean save = McpJson.getBoolean(arguments, "save", false);
      requireConfirm(arguments, "Close DBeaver editor?", save
         ? "Save and close the selected DBeaver editor?"
         : "Close the selected DBeaver editor without saving unsaved changes?");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchPage page = activePage(activeWindow());
         IEditorReference reference = resolveEditor(page, partId);
         boolean dirty = reference.isDirty();
         IEditorPart editor = reference.getEditor(true);
         boolean closed = page.closeEditor(editor, save);
         JsonObject result = new JsonObject();
         result.addProperty("part_id", partId);
         result.addProperty("closed", closed);
         result.addProperty("was_dirty", dirty);
         result.addProperty("save_requested", save);
         return result;
      });
   }

   private JsonObject openView(JsonObject arguments) throws Exception {
      String viewId = McpJson.requiredString(arguments, "view_id");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbench workbench = PlatformUI.getWorkbench();
         if (workbench.getViewRegistry().find(viewId) == null) throw new IllegalArgumentException("Unknown Eclipse view: " + viewId);
         IWorkbenchPage page = activePage(activeWindow());
         var view = page.showView(viewId);
         JsonObject result = new JsonObject();
         result.addProperty("opened", true);
         result.addProperty("view_id", viewId);
         result.addProperty("title", view.getTitle());
         return result;
      });
   }

   private JsonObject hideView(JsonObject arguments) throws Exception {
      String partId = McpJson.requiredString(arguments, "part_id");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchPage page = activePage(activeWindow());
         IWorkbenchPartReference reference = resolvePart(page, partId);
         if (!(reference instanceof IViewReference viewReference)) throw new IllegalArgumentException("part_id is not a view: " + partId);
         page.hideView(viewReference);
         JsonObject result = new JsonObject();
         result.addProperty("hidden", true);
         result.addProperty("part_id", partId);
         return result;
      });
   }

   private JsonObject switchPerspective(JsonObject arguments) throws Exception {
      String perspectiveId = McpJson.requiredString(arguments, "perspective_id");
      requireConfirm(arguments, "Switch DBeaver perspective?", "Switch the active workbench perspective to '" + perspectiveId + "'?");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbench workbench = PlatformUI.getWorkbench();
         IPerspectiveDescriptor descriptor = workbench.getPerspectiveRegistry().findPerspectiveWithId(perspectiveId);
         if (descriptor == null) throw new IllegalArgumentException("Unknown perspective: " + perspectiveId);
         IWorkbenchPage page = activePage(activeWindow());
         page.setPerspective(descriptor);
         JsonObject result = new JsonObject();
         result.addProperty("switched", true);
         result.addProperty("perspective_id", descriptor.getId());
         result.addProperty("label", descriptor.getLabel());
         return result;
      });
   }

   private JsonObject listCommands(JsonObject arguments) throws Exception {
      String search = McpJson.getString(arguments, "search", "").toLowerCase(Locale.ENGLISH);
      int limit = McpJson.getInt(arguments, "limit", 200, 1, 1000);
      return DBeaverEditorService.uiCall(() -> {
         ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
         if (service == null) throw new IllegalStateException("Eclipse command service is unavailable");
         Collection<String> ids = service.getDefinedCommandIds();
         JsonArray items = new JsonArray();
         ids.stream().sorted().filter(id -> search.isBlank() || id.toLowerCase(Locale.ENGLISH).contains(search)).limit(limit).forEach(id -> {
            Command command = service.getCommand(id);
            JsonObject item = commandPayload(command);
            item.addProperty("safe_without_confirmation", isSafeCommand(id));
            items.add(item);
         });
         JsonObject result = new JsonObject();
         result.addProperty("count", items.size());
         result.addProperty("total_defined", ids.size());
         result.add("commands", items);
         return result;
      });
   }

   private JsonObject executeCommand(JsonObject arguments) throws Exception {
      String commandId = McpJson.requiredString(arguments, "command_id");
      boolean safe = isSafeCommand(commandId);
      if (!safe) {
         if (!McpJson.getBoolean(arguments, "allow_unsafe_command", false)) {
            throw new IllegalArgumentException("Command is outside the navigation-only allowlist; set allow_unsafe_command=true after review");
         }
         requireConfirm(arguments, "Execute DBeaver command?", "Execute command '" + commandId + "'? Commands may modify editors, connections, files, or database state.");
      }
      JsonObject parameters = McpJson.getObject(arguments, "parameters");
      if (parameters.size() > 20) throw new IllegalArgumentException("At most 20 command parameters are allowed");
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchWindow window = activeWindow();
         ICommandService commandService = window.getService(ICommandService.class);
         IHandlerService handlerService = window.getService(IHandlerService.class);
         if (commandService == null || handlerService == null) throw new IllegalStateException("Eclipse command services are unavailable");
         Command command = commandService.getCommand(commandId);
         if (command == null || !command.isDefined()) throw new IllegalArgumentException("Unknown command: " + commandId);
         if (!command.isEnabled() || !command.isHandled()) throw new IllegalStateException("Command is not enabled or handled in the current context: " + commandId);
         List<Parameterization> parameterizations = new ArrayList<>();
         for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("Command parameters must be primitive values");
            IParameter parameter = command.getParameter(entry.getKey());
            if (parameter == null) throw new IllegalArgumentException("Unknown command parameter: " + entry.getKey());
            parameterizations.add(new Parameterization(parameter, entry.getValue().getAsString()));
         }
         ParameterizedCommand parameterized = new ParameterizedCommand(command, parameterizations.toArray(Parameterization[]::new));
         Object returnValue = handlerService.executeCommand(parameterized, null);
         JsonObject result = commandPayload(command);
         result.addProperty("executed", true);
         result.addProperty("safe_allowlist", safe);
         if (returnValue != null) result.addProperty("return_value", McpJson.truncate(String.valueOf(returnValue)));
         return result;
      });
   }

   private JsonObject listJobs(JsonObject arguments) {
      int limit = McpJson.getInt(arguments, "limit", 200, 1, 500);
      String search = McpJson.getString(arguments, "search", "").toLowerCase(Locale.ENGLISH);
      Job[] jobs = Job.getJobManager().find(null);
      JsonArray items = new JsonArray();
      java.util.Arrays.stream(jobs)
         .filter(job -> search.isBlank() || job.getName().toLowerCase(Locale.ENGLISH).contains(search))
         .sorted(Comparator.comparing(Job::getName))
         .limit(limit)
         .forEach(job -> items.add(jobPayload(job)));
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.addProperty("total", jobs.length);
      result.add("jobs", items);
      return result;
   }

   private JsonObject cancelJob(JsonObject arguments) throws Exception {
      String jobId = McpJson.requiredString(arguments, "job_id");
      Job job = resolveJob(jobId);
      requireConfirm(arguments, "Cancel DBeaver background job?", "Request cancellation of background job '" + job.getName() + "'? Partial work may remain.");
      boolean cancelled = job.cancel();
      JsonObject result = jobPayload(job);
      result.addProperty("cancel_requested", true);
      result.addProperty("cancelled", cancelled);
      return result;
   }

   private JsonObject listNotificationTypes(JsonObject arguments) {
      boolean includeHidden = McpJson.getBoolean(arguments, "include_hidden", false);
      JsonArray items = new JsonArray();
      NotificationRegistry.getInstance().getNotifications().stream()
         .filter(item -> includeHidden || !item.isHidden())
         .sorted(Comparator.comparing(NotificationDescriptor::getId))
         .forEach(descriptor -> items.add(notificationPayload(descriptor)));
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.add("notifications", items);
      return result;
   }

   private JsonObject getNotificationSettings(JsonObject arguments) {
      String id = McpJson.requiredString(arguments, "notification_id");
      NotificationDescriptor descriptor = NotificationRegistry.getInstance().getNotification(id);
      if (descriptor == null) throw new IllegalArgumentException("Unknown notification type: " + id);
      return notificationPayload(descriptor);
   }

   private JsonObject setNotificationSettings(JsonObject arguments) throws Exception {
      String id = McpJson.requiredString(arguments, "notification_id");
      NotificationDescriptor descriptor = NotificationRegistry.getInstance().getNotification(id);
      if (descriptor == null) throw new IllegalArgumentException("Unknown notification type: " + id);
      requireConfirm(arguments, "Change DBeaver notification settings?", "Update popup and sound settings for notification '" + descriptor.getName() + "'?");
      NotificationSettings settings = NotificationUtils.getNotificationSettings(id);
      if (arguments.has("show_popup")) settings.setShowPopup(McpJson.getBoolean(arguments, "show_popup", settings.isShowPopup()));
      if (arguments.has("play_sound")) settings.setPlaySound(McpJson.getBoolean(arguments, "play_sound", settings.isPlaySound()));
      NotificationUtils.setNotificationSettings(id, settings);
      DBWorkbench.getPlatform().getPreferenceStore().save();
      JsonObject result = notificationPayload(descriptor);
      result.addProperty("updated", true);
      return result;
   }

   private JsonObject sendTestNotification(JsonObject arguments) throws Exception {
      String title = McpJson.getString(arguments, "title", "DBeaver MCP test notification");
      String message = McpJson.getString(arguments, "message", "Notification delivery is working.");
      boolean error = McpJson.getBoolean(arguments, "error", false);
      if (title.length() > 200 || message.length() > 2000) throw new IllegalArgumentException("Notification title or message is too long");
      DBeaverEditorService.uiCall(() -> {
         DBWorkbench.getPlatformUI().showNotification(title, message, error, null);
         return null;
      });
      JsonObject result = new JsonObject();
      result.addProperty("sent", true);
      result.addProperty("error", error);
      return result;
   }

   private static JsonObject partPayload(IWorkbenchPartReference reference, boolean active) {
      JsonObject item = new JsonObject();
      item.addProperty("part_id", partId(reference));
      item.addProperty("site_id", reference.getId());
      item.addProperty("title", reference.getTitle());
      item.addProperty("part_name", reference.getPartName());
      item.addProperty("tooltip", reference.getTitleToolTip());
      item.addProperty("active", active);
      return item;
   }

   private static JsonObject commandPayload(Command command) {
      JsonObject item = new JsonObject();
      item.addProperty("id", command.getId());
      item.addProperty("defined", command.isDefined());
      item.addProperty("enabled", command.isEnabled());
      item.addProperty("handled", command.isHandled());
      if (command.isDefined()) {
         try {
            item.addProperty("name", command.getName());
            item.addProperty("description", command.getDescription());
            if (command.getCategory() != null && command.getCategory().isDefined()) item.addProperty("category", command.getCategory().getName());
            JsonArray parameters = new JsonArray();
            IParameter[] declaredParameters = command.getParameters();
            if (declaredParameters != null) {
               for (IParameter parameter : declaredParameters) {
                  JsonObject p = new JsonObject();
                  p.addProperty("id", parameter.getId());
                  p.addProperty("name", parameter.getName());
                  p.addProperty("optional", parameter.isOptional());
                  parameters.add(p);
               }
            }
            item.add("parameters", parameters);
         } catch (NotDefinedException e) {
            item.addProperty("definition_error", McpJson.safeMessage(e));
         }
      }
      return item;
   }

   private static JsonObject jobPayload(Job job) {
      JsonObject item = new JsonObject();
      item.addProperty("job_id", jobId(job));
      item.addProperty("name", job.getName());
      item.addProperty("state", jobState(job.getState()));
      item.addProperty("priority", job.getPriority());
      item.addProperty("user", job.isUser());
      item.addProperty("system", job.isSystem());
      IStatus result = job.getResult();
      if (result != null) {
         item.addProperty("result_severity", result.getSeverity());
         item.addProperty("result_message", McpJson.truncate(result.getMessage()));
      }
      return item;
   }

   private static JsonObject notificationPayload(NotificationDescriptor descriptor) {
      NotificationSettings settings = NotificationUtils.getNotificationSettings(descriptor.getId());
      JsonObject item = new JsonObject();
      item.addProperty("id", descriptor.getId());
      item.addProperty("name", descriptor.getName());
      item.addProperty("description", descriptor.getDescription());
      item.addProperty("hidden", descriptor.isHidden());
      item.addProperty("sound_supported", descriptor.isSoundEnabled());
      item.addProperty("show_popup", settings.isShowPopup());
      item.addProperty("play_sound", settings.isPlaySound());
      item.addProperty("custom_sound", settings.getSoundFile() != null);
      return item;
   }

   private static IWorkbenchWindow activeWindow() {
      IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window == null) throw new IllegalStateException("DBeaver workbench window is unavailable");
      return window;
   }

   private static IWorkbenchPage activePage(IWorkbenchWindow window) {
      IWorkbenchPage page = window.getActivePage();
      if (page == null) throw new IllegalStateException("DBeaver workbench page is unavailable");
      return page;
   }

   private static IWorkbenchPartReference resolvePart(IWorkbenchPage page, String id) {
      for (IEditorReference reference : page.getEditorReferences()) if (partId(reference).equals(id)) return reference;
      for (IViewReference reference : page.getViewReferences()) if (partId(reference).equals(id)) return reference;
      throw new IllegalArgumentException("Unknown workbench part_id: " + id);
   }

   private static IEditorReference resolveEditor(IWorkbenchPage page, String id) {
      IWorkbenchPartReference reference = resolvePart(page, id);
      if (!(reference instanceof IEditorReference editorReference)) throw new IllegalArgumentException("part_id is not an editor: " + id);
      return editorReference;
   }

   private static Job resolveJob(String id) {
      for (Job job : Job.getJobManager().find(null)) if (jobId(job).equals(id)) return job;
      throw new IllegalArgumentException("Unknown or completed Eclipse job: " + id);
   }

   private static String partId(IWorkbenchPartReference reference) {
      return "part-" + Integer.toHexString(System.identityHashCode(reference));
   }

   private static String jobId(Job job) {
      return "eclipse-job-" + Integer.toHexString(System.identityHashCode(job));
   }

   private static String jobState(int state) {
      return switch (state) {
         case Job.NONE -> "none";
         case Job.WAITING -> "waiting";
         case Job.SLEEPING -> "sleeping";
         case Job.RUNNING -> "running";
         default -> "unknown";
      };
   }

   private static boolean isSafeCommand(String commandId) {
      return SAFE_COMMANDS.contains(commandId) || SAFE_COMMAND_PREFIXES.stream().anyMatch(commandId::startsWith);
   }

   private static void requireConfirm(JsonObject arguments, String title, String message) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      if (!DBeaverNativeConfirmation.confirm(title, message)) throw new IllegalStateException("Operation cancelled by the DBeaver user");
   }
}
