/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.editors.erd.editor.ERDEditorEmbedded;
import org.jkiss.dbeaver.ui.editors.erd.editor.ERDEditorPart;
import org.jkiss.dbeaver.ui.editors.erd.export.ERDExportGraphML;
import org.jkiss.dbeaver.ui.editors.erd.export.ERDExportRasterImage;
import org.jkiss.dbeaver.ui.editors.erd.model.DiagramLoader;
import org.jkiss.dbeaver.ui.editors.erd.model.EntityDiagram;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

final class DBeaverVisualService {
   private final DBeaverConnectionService connections = new DBeaverConnectionService();
   private final DBeaverObjectService objects = new DBeaverObjectService(this.connections);
   private final Map<String, WeakReference<ERDEditorPart>> editors = new ConcurrentHashMap<>();
   private final Map<ERDEditorPart, String> editorIds = Collections.synchronizedMap(new WeakHashMap<>());

   JsonObject execute(String action, JsonObject arguments) throws Exception {
      return switch (action) {
         case "open_erd" -> open(arguments);
         case "list_editors" -> listEditors();
         case "active_editor" -> activeEditor();
         case "state" -> state(arguments);
         case "refresh" -> refresh(arguments);
         case "auto_layout" -> autoLayout(arguments);
         case "save" -> save(arguments);
         case "export" -> export(arguments);
         default -> throw new IllegalArgumentException("Unknown visual action: " + action);
      };
   }

   private JsonObject open(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.objects.resolve(connection, arguments);
      DBNDatabaseNode node = DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(new VoidProgressMonitor(), object, false);
      if (node == null) throw new IllegalStateException("DBeaver navigator node is unavailable for " + object.getName());
      IEditorPart part = DBeaverEditorService.uiCall(() -> NavigatorHandlerObjectOpen.openEntityEditor(
         node, ERDEditorEmbedded.class.getName(), null, Map.of(), requireWindow(), true));
      if (!(part instanceof ERDEditorPart editor)) throw new IllegalStateException("DBeaver could not open the ER diagram editor");
      waitLoaded(editor);
      JsonObject result = statePayload(register(editor), editor);
      result.addProperty("opened", true);
      return result;
   }

   private JsonObject listEditors() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         JsonArray items = new JsonArray();
         for (IEditorReference reference : requireWindow().getActivePage().getEditorReferences()) {
            IEditorPart part = reference.getEditor(false);
            if (part instanceof ERDEditorPart editor) items.add(statePayload(register(editor), editor));
         }
         JsonObject result = new JsonObject();
         result.addProperty("count", items.size());
         result.add("editors", items);
         return result;
      });
   }

   private JsonObject activeEditor() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IEditorPart part = requireWindow().getActivePage().getActiveEditor();
         JsonObject result = new JsonObject();
         if (part instanceof ERDEditorPart editor) {
            result = statePayload(register(editor), editor);
            result.addProperty("active", true);
         } else result.addProperty("active", false);
         return result;
      });
   }

   private JsonObject state(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ERDEditorPart editor = resolve(arguments);
         return statePayload(register(editor), editor);
      });
   }

   private JsonObject refresh(JsonObject arguments) throws Exception {
      boolean rearrange = McpJson.getBoolean(arguments, "rearrange", false);
      boolean reload = McpJson.getBoolean(arguments, "reload", true);
      boolean refreshMetadata = McpJson.getBoolean(arguments, "refresh_metadata", false);
      return DBeaverEditorService.uiCall(() -> {
         ERDEditorPart editor = resolve(arguments);
         editor.refreshDiagram(rearrange, reload, refreshMetadata);
         JsonObject result = statePayload(register(editor), editor);
         result.addProperty("refresh_requested", true);
         return result;
      });
   }

   private JsonObject autoLayout(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ERDEditorPart editor = resolve(arguments);
         editor.refreshDiagram(true, false, false);
         JsonObject result = statePayload(register(editor), editor);
         result.addProperty("layout_requested", true);
         return result;
      });
   }

   private JsonObject save(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      ERDEditorPart editor = DBeaverEditorService.uiCall(() -> resolve(arguments));
      if (!DBeaverNativeConfirmation.confirm("Save DBeaver ER diagram?", "Save changes in ER diagram '" + editor.getTitle() + "'?")) {
         throw new IllegalStateException("Operation cancelled by the DBeaver user");
      }
      return DBeaverEditorService.uiCall(() -> {
         editor.doSave(new NullProgressMonitor());
         JsonObject result = statePayload(register(editor), editor);
         result.addProperty("saved", !editor.isDirty());
         return result;
      });
   }

   private JsonObject export(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) throw new IllegalArgumentException("confirm=true is required");
      String format = McpJson.getString(arguments, "format", "png").toLowerCase();
      if (!format.equals("png") && !format.equals("graphml") && !format.equals("erd")) throw new IllegalArgumentException("format must be png, graphml, or erd");
      DBeaverTransferPathPolicy paths = new DBeaverTransferPathPolicy();
      Path output = paths.resolveOutput(McpJson.requiredString(arguments, "path"));
      if (Files.exists(output) && !McpJson.getBoolean(arguments, "overwrite", false)) throw new IllegalArgumentException("Export file exists; pass overwrite=true: " + output);
      ERDEditorPart editor = DBeaverEditorService.uiCall(() -> resolve(arguments));
      if (!DBeaverNativeConfirmation.confirm("Export DBeaver ER diagram?", "Export ER diagram '" + editor.getTitle() + "' to " + output + "?")) {
         throw new IllegalStateException("Operation cancelled by the DBeaver user");
      }
      DBeaverEditorService.uiCall(() -> {
         EntityDiagram diagram = requireDiagram(editor);
         switch (format) {
            case "png" -> new ERDExportRasterImage().exportDiagram(diagram, editor.getDiagramPart().getFigure(), editor.getDiagramPart(), output.toFile());
            case "graphml" -> new ERDExportGraphML().exportDiagram(diagram, editor.getDiagramPart().getFigure(), editor.getDiagramPart(), output.toFile());
            case "erd" -> {
               String serialized = DiagramLoader.serializeDiagram(new VoidProgressMonitor(), editor.getDiagramPart(), diagram, false, false);
               Files.writeString(output, serialized, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
         }
         return null;
      });
      if (!Files.isRegularFile(output) || Files.size(output) == 0) throw new IllegalStateException("ERD export did not produce a file");
      JsonObject result = new JsonObject();
      result.addProperty("exported", true);
      result.addProperty("format", format);
      result.addProperty("path", output.toString());
      result.addProperty("bytes", Files.size(output));
      return result;
   }

   private void waitLoaded(ERDEditorPart editor) throws Exception {
      for (int attempt = 0; attempt < 100; attempt++) {
         if (DBeaverEditorService.uiCall(() -> editor.isLoaded() && editor.getDiagram() != null)) return;
         Thread.sleep(50L);
      }
      throw new IllegalStateException("The native DBeaver ER diagram did not become ready");
   }

   private String register(ERDEditorPart editor) {
      String existing = this.editorIds.get(editor);
      if (existing != null) return existing;
      String id = "erd-" + UUID.randomUUID();
      this.editorIds.put(editor, id);
      this.editors.put(id, new WeakReference<>(editor));
      return id;
   }

   private ERDEditorPart resolve(JsonObject arguments) {
      String id = McpJson.getString(arguments, "editor_id", "").trim();
      if (id.isEmpty()) {
         IEditorPart active = requireWindow().getActivePage().getActiveEditor();
         if (active instanceof ERDEditorPart editor) return editor;
         throw new IllegalStateException("No active DBeaver ER diagram editor");
      }
      WeakReference<ERDEditorPart> reference = this.editors.get(id);
      ERDEditorPart editor = reference == null ? null : reference.get();
      if (editor == null || editor.getSite().getShell().isDisposed()) {
         this.editors.remove(id);
         throw new IllegalArgumentException("ERD editor not found or already closed: " + id);
      }
      return editor;
   }

   private static JsonObject statePayload(String id, ERDEditorPart editor) {
      JsonObject result = new JsonObject();
      result.addProperty("editor_id", id);
      result.addProperty("title", editor.getTitle());
      result.addProperty("loaded", editor.isLoaded());
      result.addProperty("dirty", editor.isDirty());
      result.addProperty("read_only", editor.isReadOnly());
      result.addProperty("active_task", editor.isActiveTask());
      EntityDiagram diagram = editor.getDiagram();
      if (diagram != null) {
         result.addProperty("diagram", diagram.getName());
         JsonArray entities = new JsonArray();
         int associations = 0;
         for (var entity : diagram.getEntities()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entity.getName());
            if (entity.getObject() != null) item.add("object", DBeaverObjectService.identity(entity.getObject()));
            item.addProperty("associations", entity.getAssociations().size());
            associations += entity.getAssociations().size();
            entities.add(item);
         }
         result.addProperty("entity_count", entities.size());
         result.addProperty("association_references", associations);
         result.add("entities", entities);
      }
      return result;
   }

   private static EntityDiagram requireDiagram(ERDEditorPart editor) {
      EntityDiagram diagram = editor.getDiagram();
      if (diagram == null || editor.getDiagramPart() == null) throw new IllegalStateException("ER diagram is not loaded");
      return diagram;
   }

   private static IWorkbenchWindow requireWindow() {
      IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window == null || window.getActivePage() == null) throw new IllegalStateException("DBeaver workbench window is unavailable");
      return window;
   }
}
