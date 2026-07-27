/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController.ColumnOrder;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController.RowPlacement;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetSelection;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetModel;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetSaveSettings;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.editors.data.DatabaseDataEditor;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

final class DBeaverDataEditorService {
   private static final int MAX_PAGE_ROWS = 200;
   private static final int MAX_PENDING_ROWS = 500;
   private static final int MAX_TRANSFER_CELL_CHARS = 16_384;

   private final DBeaverConnectionService connections = new DBeaverConnectionService();
   private final DBeaverObjectService objects = new DBeaverObjectService(this.connections);
   private final Map<String, WeakReference<ResultSetViewer>> editors = new ConcurrentHashMap<>();
   private final Map<ResultSetViewer, String> editorIds = Collections.synchronizedMap(new WeakHashMap<>());

   JsonObject openTable(JsonObject arguments) throws Exception {
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(arguments);
      DBSObject object = this.objects.resolve(connection, arguments);
      if (!(object instanceof DBSDataContainer dataContainer)) {
         throw new IllegalArgumentException("The selected object is not a data container");
      }
      DBDDataFilter filter = new DBDDataFilter();
      filter.setWhere(blankToNull(McpJson.getString(arguments, "where", "")));
      filter.setOrder(blankToNull(McpJson.getString(arguments, "order", "")));
      DBNDatabaseNode node = DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(new VoidProgressMonitor(), dataContainer, false);
      if (node == null) {
         throw new IllegalStateException("DBeaver navigator node is unavailable for " + dataContainer.getName());
      }
      IEditorPart editor = DBeaverEditorService.uiCall(() -> NavigatorHandlerObjectOpen.openEntityEditor(
         node,
         DatabaseDataEditor.class.getName(),
         null,
         Map.of(DatabaseDataEditor.ATTR_DATA_FILTER, filter),
         requireWindow(),
         true
      ));
      if (editor == null) {
         throw new IllegalStateException("DBeaver could not open the data editor");
      }
      ResultSetViewer viewer = waitForViewer(editor);
      JsonObject result = statePayload(register(viewer), viewer);
      result.addProperty("opened", true);
      return result;
   }

   JsonObject listEditors() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         JsonArray items = new JsonArray();
         IWorkbenchPage page = requireWindow().getActivePage();
         for (IEditorReference reference : page.getEditorReferences()) {
            IEditorPart editor = reference.getEditor(false);
            ResultSetViewer viewer = adaptViewer(editor);
            if (viewer != null && !viewer.getControl().isDisposed()) {
               items.add(statePayload(register(viewer), viewer));
            }
         }
         JsonObject result = new JsonObject();
         result.addProperty("count", items.size());
         result.add("editors", items);
         return result;
      });
   }

   JsonObject activeEditor() throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = adaptViewer(requireWindow().getActivePage().getActiveEditor());
         JsonObject result = new JsonObject();
         if (viewer == null) {
            result.addProperty("active", false);
         } else {
            result = statePayload(register(viewer), viewer);
            result.addProperty("active", true);
         }
         return result;
      });
   }

   JsonObject state(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         return statePayload(register(viewer), viewer);
      });
   }

   JsonObject fetchRows(JsonObject arguments) throws Exception {
      int offset = McpJson.getInt(arguments, "offset", 0, 0, 1_000_000);
      int limit = McpJson.getInt(arguments, "limit", 100, 1, MAX_PAGE_ROWS);
      boolean maskSensitive = McpJson.getBoolean(arguments, "mask_sensitive", true);
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         return rowsPayload(register(viewer), viewer, offset, limit, maskSensitive);
      });
   }

   JsonObject setFilter(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         DBDDataFilter filter = new DBDDataFilter(viewer.getDataFilter());
         filter.setWhere(blankToNull(McpJson.getString(arguments, "where", "")));
         viewer.setDataFilter(filter, McpJson.getBoolean(arguments, "refresh", true));
         return statePayload(register(viewer), viewer);
      });
   }

   JsonObject clearFilter(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         DBDDataFilter filter = new DBDDataFilter(viewer.getDataFilter());
         filter.setWhere(null);
         viewer.setDataFilter(filter, McpJson.getBoolean(arguments, "refresh", true));
         return statePayload(register(viewer), viewer);
      });
   }

   JsonObject setSort(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         String column = McpJson.getString(arguments, "column", "").trim();
         if (!column.isEmpty()) {
            DBDAttributeBinding attribute = findAttribute(viewer, column);
            String direction = McpJson.getString(arguments, "direction", "asc").toLowerCase();
            ColumnOrder order = switch (direction) {
               case "asc" -> ColumnOrder.ASC;
               case "desc" -> ColumnOrder.DESC;
               case "none" -> ColumnOrder.NONE;
               default -> throw new IllegalArgumentException("direction must be asc, desc, or none");
            };
            viewer.toggleSortOrder(attribute, order);
         } else {
            DBDDataFilter filter = new DBDDataFilter(viewer.getDataFilter());
            filter.setOrder(blankToNull(McpJson.getString(arguments, "order", "")));
            viewer.setDataFilter(filter, McpJson.getBoolean(arguments, "refresh", true));
         }
         return statePayload(register(viewer), viewer);
      });
   }

   JsonObject nextPage(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         if (!viewer.isRefreshInProgress()) {
            viewer.readNextSegment();
         }
         JsonObject result = statePayload(register(viewer), viewer);
         result.addProperty("next_segment_requested", true);
         return result;
      });
   }

   JsonObject refresh(JsonObject arguments) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         boolean started = viewer.refreshData(null);
         JsonObject result = statePayload(register(viewer), viewer);
         result.addProperty("refresh_started", started);
         return result;
      });
   }

   JsonObject editCell(JsonObject arguments) throws Exception {
      int rowIndex = McpJson.getInt(arguments, "row_index", -1, 0, 1_000_000);
      String column = McpJson.requiredString(arguments, "column");
      JsonElement value = arguments.has("value") ? arguments.get("value") : JsonNull.INSTANCE;
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         requireEditable(viewer);
         ResultSetRow row = requireRow(viewer, rowIndex);
         DBDAttributeBinding attribute = findAttribute(viewer, column);
         Object converted = convertValue(viewer, attribute, value);
         boolean updated = viewer.updateCellValue(attribute, row, null, converted, true);
         viewer.redrawData(false, true);
         viewer.updateDirtyFlag();
         viewer.updateEditControls();
         JsonObject result = pendingPayload(viewer, 20);
         result.addProperty("updated", updated);
         result.addProperty("row_index", rowIndex);
         result.addProperty("column", attribute.getName());
         return result;
      });
   }

   JsonObject insertRow(JsonObject arguments) throws Exception {
      JsonObject values = McpJson.getObject(arguments, "values");
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         requireEditable(viewer);
         validateValues(viewer, values);
         ResultSetRow row = viewer.addNewRow(RowPlacement.AT_END, false, false);
         try {
            applyValues(viewer, row, values);
         } catch (Exception e) {
            deleteSpecificRows(viewer, List.of(row));
            throw e;
         }
         viewer.redrawData(false, true);
         viewer.updateDirtyFlag();
         viewer.updateEditControls();
         JsonObject result = pendingPayload(viewer, 20);
         result.addProperty("inserted", true);
         result.addProperty("row_index", row.getVisualNumber());
         return result;
      });
   }

   JsonObject deleteRows(JsonObject arguments) throws Exception {
      int[] indexes = intArray(arguments, "row_indexes", 500);
      if (indexes.length == 0) {
         throw new IllegalArgumentException("row_indexes must contain at least one row index");
      }
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         requireEditable(viewer);
         for (int index : indexes) {
            requireRow(viewer, index);
         }
         List<ResultSetRow> selectedRows = new ArrayList<>(indexes.length);
         for (int index : indexes) {
            selectedRows.add(viewer.getModel().getRow(index));
         }
         deleteSpecificRows(viewer, selectedRows);
         viewer.redrawData(false, true);
         viewer.updateDirtyFlag();
         viewer.updateEditControls();
         JsonObject result = pendingPayload(viewer, 50);
         result.addProperty("rows_marked_for_delete", indexes.length);
         return result;
      });
   }

   JsonObject pendingChanges(JsonObject arguments) throws Exception {
      int limit = McpJson.getInt(arguments, "limit", 100, 1, MAX_PENDING_ROWS);
      return DBeaverEditorService.uiCall(() -> pendingPayload(resolve(arguments), limit));
   }

   String saveChanges(JsonObject arguments, DBeaverMcpJobManager jobs) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) {
         throw new IllegalArgumentException("confirm=true is required before opening DBeaver's native save confirmation");
      }
      ResultSetViewer viewer = DBeaverEditorService.uiCall(() -> resolve(arguments));
      String editorId = register(viewer);
      return jobs.submit("data-workflows", "save-data-editor", false, context -> DBeaverEditorService.uiCall(() -> {
         context.checkCancelled();
         boolean saved = viewer.applyChanges(new VoidProgressMonitor(), new ResultSetSaveSettings());
         JsonObject result = statePayload(editorId, viewer);
         result.addProperty("saved", saved);
         return result;
      }));
   }

   JsonObject rejectChanges(JsonObject arguments) throws Exception {
      if (!McpJson.getBoolean(arguments, "confirm", false)) {
         throw new IllegalArgumentException("confirm=true is required to reject pending changes");
      }
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         viewer.rejectChanges();
         JsonObject result = statePayload(register(viewer), viewer);
         result.addProperty("rejected", true);
         return result;
      });
   }

   TransferState transferState(String editorId) throws Exception {
      JsonObject arguments = new JsonObject();
      arguments.addProperty("editor_id", editorId);
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         ResultSetModel model = viewer.getModel();
         int availableRows = 0;
         for (ResultSetRow row : model.getAllRows()) {
            if (row.getState() != ResultSetRow.STATE_REMOVED) {
               availableRows++;
            }
         }
         return new TransferState(
            model.getVisibleLeafAttributes().size(),
            availableRows,
            DBeaverObjectService.dmlName(viewer.getDataContainer())
         );
      });
   }

   TransferSnapshot snapshotTransfer(String editorId, int maxRows, long maxBytes, boolean maskSensitive) throws Exception {
      JsonObject arguments = new JsonObject();
      arguments.addProperty("editor_id", editorId);
      return DBeaverEditorService.uiCall(() -> {
         ResultSetViewer viewer = resolve(arguments);
         ResultSetModel model = viewer.getModel();
         List<DBDAttributeBinding> attributes = model.getVisibleLeafAttributes();
         List<String> columns = attributes.stream().map(DBDAttributeBinding::getName).toList();
         List<Map<String, Object>> rows = new ArrayList<>();
         long estimatedBytes = columns.stream().mapToLong(name -> estimateBytes(name)).sum();
         boolean truncatedByRows = false;
         boolean truncatedByBytes = false;
         for (int rowIndex = 0; rowIndex < model.getRowCount(); rowIndex++) {
            ResultSetRow row = model.getRow(rowIndex);
            if (row.getState() == ResultSetRow.STATE_REMOVED) {
               continue;
            }
            if (rows.size() >= maxRows) {
               truncatedByRows = true;
               break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            long rowBytes = 0;
            for (DBDAttributeBinding attribute : attributes) {
               Object value = plainValue(model.getCellValue(attribute, row));
               String category = SensitiveDataPolicy.classify(attribute.getName(), attribute.getFullTypeName());
               if (category != null && (maskSensitive || SensitiveDataPolicy.alwaysMask(category))) {
                  value = "<masked:" + category + ">";
               }
               item.put(attribute.getName(), value);
               rowBytes += estimateBytes(attribute.getName()) + estimateBytes(value);
            }
            if (estimatedBytes + rowBytes > maxBytes) {
               truncatedByBytes = true;
               break;
            }
            estimatedBytes += rowBytes;
            rows.add(Collections.unmodifiableMap(item));
         }
         return new TransferSnapshot(
            columns,
            rows,
            DBeaverObjectService.dmlName(viewer.getDataContainer()),
            estimatedBytes,
            truncatedByRows,
            truncatedByBytes
         );
      });
   }

   int stageRows(String editorId, List<Map<String, Object>> rows, DBeaverMcpJobManager.JobContext context) throws Exception {
      int staged = 0;
      List<ResultSetRow> stagedRows = new ArrayList<>();
      try {
         for (int offset = 0; offset < rows.size(); offset += 100) {
            context.checkCancelled();
            int from = offset;
            int to = Math.min(rows.size(), offset + 100);
            List<ResultSetRow> batchRows = DBeaverEditorService.uiCall(() -> {
               JsonObject arguments = new JsonObject();
               arguments.addProperty("editor_id", editorId);
               ResultSetViewer viewer = resolve(arguments);
               requireEditable(viewer);
               for (Map<String, Object> values : rows.subList(from, to)) {
                  validateValues(viewer, McpJson.GSON.toJsonTree(values).getAsJsonObject());
               }
               List<ResultSetRow> localRows = new ArrayList<>();
               try {
                  for (Map<String, Object> values : rows.subList(from, to)) {
                     ResultSetRow row = viewer.addNewRow(RowPlacement.AT_END, false, false);
                     localRows.add(row);
                     applyValues(viewer, row, McpJson.GSON.toJsonTree(values).getAsJsonObject());
                  }
               } catch (Exception e) {
                  deleteSpecificRows(viewer, localRows);
                  throw e;
               }
               viewer.redrawData(false, true);
               viewer.updateDirtyFlag();
               viewer.updateEditControls();
               return List.copyOf(localRows);
            });
            stagedRows.addAll(batchRows);
            staged += batchRows.size();
         }
         return staged;
      } catch (Exception e) {
         if (!stagedRows.isEmpty()) {
            DBeaverEditorService.uiCall(() -> {
               JsonObject arguments = new JsonObject();
               arguments.addProperty("editor_id", editorId);
               ResultSetViewer viewer = resolve(arguments);
               deleteSpecificRows(viewer, stagedRows);
               viewer.redrawData(false, true);
               viewer.updateDirtyFlag();
               viewer.updateEditControls();
               return null;
            });
         }
         throw e;
      }
   }

   private ResultSetViewer waitForViewer(IEditorPart editor) throws Exception {
      for (int attempt = 0; attempt < 100; attempt++) {
         ResultSetViewer viewer = DBeaverEditorService.uiCall(() -> adaptViewer(editor));
         if (viewer != null && !viewer.getControl().isDisposed()) {
            return viewer;
         }
         Thread.sleep(50L);
      }
      throw new IllegalStateException("The native DBeaver data viewer did not become ready");
   }

   private String register(ResultSetViewer viewer) {
      String existing = this.editorIds.get(viewer);
      if (existing != null) {
         return existing;
      }
      String id = "data-" + UUID.randomUUID();
      this.editorIds.put(viewer, id);
      this.editors.put(id, new WeakReference<>(viewer));
      return id;
   }

   private ResultSetViewer resolve(JsonObject arguments) {
      String id = McpJson.getString(arguments, "editor_id", "").trim();
      if (id.isEmpty()) {
         ResultSetViewer active = adaptViewer(requireWindow().getActivePage().getActiveEditor());
         if (active == null) {
            throw new IllegalStateException("No active DBeaver data editor");
         }
         return active;
      }
      WeakReference<ResultSetViewer> reference = this.editors.get(id);
      ResultSetViewer viewer = reference == null ? null : reference.get();
      if (viewer == null || viewer.getControl().isDisposed()) {
         this.editors.remove(id);
         throw new IllegalArgumentException("Data editor not found or already closed: " + id);
      }
      return viewer;
   }

   private static ResultSetViewer adaptViewer(IEditorPart editor) {
      return editor == null ? null : editor.getAdapter(ResultSetViewer.class);
   }

   private static IWorkbenchWindow requireWindow() {
      IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window == null || window.getActivePage() == null) {
         throw new IllegalStateException("DBeaver workbench window is unavailable");
      }
      return window;
   }

   private static JsonObject statePayload(String editorId, ResultSetViewer viewer) {
      ResultSetModel model = viewer.getModel();
      JsonObject result = new JsonObject();
      result.addProperty("editor_id", editorId);
      result.addProperty("title", viewer.getSite().getPart().getTitle());
      result.addProperty("row_count", model.getRowCount());
      if (model.getTotalRowCount() != null) {
         result.addProperty("total_row_count", model.getTotalRowCount());
      }
      result.addProperty("dirty", viewer.isDirty());
      result.addProperty("refreshing", viewer.isRefreshInProgress());
      result.addProperty("supports_edit", viewer.supportsEdit());
      result.addProperty("read_only", viewer.isAllAttributesReadOnly());
      result.addProperty("supports_filter", viewer.supportsDataFilter());
      if (viewer.getReadOnlyStatus() != null) {
         result.addProperty("read_only_reason", viewer.getReadOnlyStatus());
      }
      DBSDataContainer container = viewer.getDataContainer();
      if (container != null) {
         result.add("object", DBeaverObjectService.identity(container));
         if (container.getDataSource() != null) {
            result.add("connection", DBeaverConnectionService.connectionPayload(container.getDataSource().getContainer()));
         }
      }
      DBDDataFilter filter = viewer.getDataFilter();
      JsonObject filterJson = new JsonObject();
      filterJson.addProperty("where", filter.getWhere() == null ? "" : filter.getWhere());
      filterJson.addProperty("order", filter.getOrder() == null ? "" : filter.getOrder());
      result.add("filter", filterJson);
      result.add("columns", columnsPayload(model));
      return result;
   }

   private static JsonObject rowsPayload(String editorId, ResultSetViewer viewer, int offset, int limit, boolean maskSensitive) {
      ResultSetModel model = viewer.getModel();
      JsonObject result = statePayload(editorId, viewer);
      result.addProperty("offset", offset);
      result.addProperty("limit", limit);
      JsonArray rows = new JsonArray();
      List<DBDAttributeBinding> attributes = model.getVisibleLeafAttributes();
      int end = Math.min(model.getRowCount(), offset + limit);
      for (int index = offset; index < end; index++) {
         ResultSetRow row = model.getRow(index);
         JsonObject item = new JsonObject();
         item.addProperty("_row_index", index);
         item.addProperty("_row_state", rowState(row));
         for (DBDAttributeBinding attribute : attributes) {
            item.add(attribute.getName(), McpJson.toJsonValue(model.getCellValue(attribute, row)));
         }
         rows.add(item);
      }
      result.add("rows", rows);
      result.addProperty("returned", rows.size());
      return SensitiveDataPolicy.maskQueryPayload(result, maskSensitive);
   }

   private static JsonArray columnsPayload(ResultSetModel model) {
      JsonArray columns = new JsonArray();
      for (DBDAttributeBinding attribute : model.getVisibleLeafAttributes()) {
         JsonObject item = new JsonObject();
         item.addProperty("name", attribute.getName());
         item.addProperty("label", attribute.getName());
         item.addProperty("type", attribute.getFullTypeName());
         item.addProperty("data_kind", attribute.getDataKind().name().toLowerCase());
         item.addProperty("ordinal", attribute.getOrdinalPosition());
         columns.add(item);
      }
      return columns;
   }

   private static JsonObject pendingPayload(ResultSetViewer viewer, int limit) {
      JsonArray rows = new JsonArray();
      int total = 0;
      for (ResultSetRow row : viewer.getModel().getAllRows()) {
         if (row.getState() == ResultSetRow.STATE_NORMAL && !row.isChanged()) {
            continue;
         }
         total++;
         if (rows.size() >= limit) {
            continue;
         }
         JsonObject item = new JsonObject();
         item.addProperty("row_index", row.getVisualNumber());
         item.addProperty("state", rowState(row));
         JsonArray changes = new JsonArray();
         for (DBDAttributeBinding attribute : row.getChangedAttributes()) {
            JsonObject change = new JsonObject();
            change.addProperty("column", attribute.getName());
            String category = SensitiveDataPolicy.classify(attribute.getName(), attribute.getFullTypeName());
            if (category != null) {
               change.addProperty("sensitive_category", category);
               change.addProperty("old_value", "<masked:" + category + ">");
               change.addProperty("new_value", "<masked:" + category + ">");
            } else {
               change.add("old_value", McpJson.toJsonValue(row.getChange(attribute)));
               change.add("new_value", McpJson.toJsonValue(viewer.getModel().getCellValue(attribute, row)));
            }
            changes.add(change);
         }
         item.add("changes", changes);
         rows.add(item);
      }
      JsonObject result = new JsonObject();
      result.addProperty("dirty", viewer.isDirty());
      result.addProperty("pending_rows", total);
      result.addProperty("returned", rows.size());
      result.addProperty("truncated", total > rows.size());
      result.add("rows", rows);
      return SensitiveDataPolicy.maskQueryPayload(result, true);
   }

   private static void applyValues(ResultSetViewer viewer, ResultSetRow row, JsonObject values) throws Exception {
      DBCExecutionContext context = viewer.getExecutionContext();
      if (context == null) {
         for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            DBDAttributeBinding attribute = findAttribute(viewer, entry.getKey());
            viewer.updateCellValue(attribute, row, null, rawValue(entry.getValue()), true);
         }
         return;
      }
      try (DBCSession session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.USER, "MCP data editor row conversion")) {
         for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            DBDAttributeBinding attribute = findAttribute(viewer, entry.getKey());
            Object converted = convertValue(attribute, entry.getValue(), session);
            viewer.updateCellValue(attribute, row, null, converted, true);
         }
      }
   }

   private static void validateValues(ResultSetViewer viewer, JsonObject values) {
      for (String name : values.keySet()) {
         findAttribute(viewer, name);
      }
   }

   private static Object convertValue(ResultSetViewer viewer, DBDAttributeBinding attribute, JsonElement value) throws Exception {
      DBCExecutionContext context = viewer.getExecutionContext();
      if (context == null) {
         return rawValue(value);
      }
      try (DBCSession session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.USER, "MCP data editor value conversion")) {
         return convertValue(attribute, value, session);
      }
   }

   private static Object convertValue(DBDAttributeBinding attribute, JsonElement value, DBCSession session) throws Exception {
      Object raw = rawValue(value);
      DBSTypedObject type = attribute.getPresentationAttribute();
      if (type == null) {
         type = attribute.getAttribute();
      }
      return type == null ? raw : attribute.getValueHandler().getValueFromObject(session, type, raw, false, true);
   }

   private static Object rawValue(JsonElement value) {
      if (value == null || value.isJsonNull()) return null;
      if (!value.isJsonPrimitive()) return McpJson.GSON.fromJson(value, Object.class);
      if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
      if (value.getAsJsonPrimitive().isNumber()) return new BigDecimal(value.getAsString());
      return value.getAsString();
   }

   private static void deleteSpecificRows(ResultSetViewer viewer, List<ResultSetRow> rows) {
      if (rows.isEmpty()) return;
      boolean restoreRecordMode = viewer.isRecordMode();
      if (restoreRecordMode) {
         viewer.toggleMode();
      }
      if (!(viewer.getActivePresentation() instanceof ISelectionProvider selectionProvider)) {
         if (restoreRecordMode && !viewer.isRecordMode()) {
            viewer.toggleMode();
         }
         throw new IllegalStateException("The current Data Editor presentation does not support row selection");
      }
      ISelection previous = selectionProvider.getSelection();
      try {
         selectionProvider.setSelection(new McpResultSetSelection(viewer, rows));
         viewer.deleteSelectedRows();
      } finally {
         selectionProvider.setSelection(previous);
         if (restoreRecordMode && !viewer.isRecordMode()) {
            viewer.toggleMode();
         }
      }
   }

   private static DBDAttributeBinding findAttribute(ResultSetViewer viewer, String name) {
      for (DBDAttributeBinding attribute : viewer.getModel().getVisibleLeafAttributes()) {
         if (attribute.getName().equalsIgnoreCase(name)) {
            return attribute;
         }
      }
      throw new IllegalArgumentException("Data editor column not found: " + name);
   }

   private static ResultSetRow requireRow(ResultSetViewer viewer, int index) {
      if (index < 0 || index >= viewer.getModel().getRowCount()) {
         throw new IllegalArgumentException("row_index is outside the current result set: " + index);
      }
      return viewer.getModel().getRow(index);
   }

   private static void requireEditable(ResultSetViewer viewer) {
      if (!viewer.supportsEdit() || viewer.isAllAttributesReadOnly()) {
         String reason = viewer.getReadOnlyStatus();
         throw new IllegalStateException(reason == null ? "The current data editor is read-only" : reason);
      }
   }

   private static int[] intArray(JsonObject arguments, String name, int maximum) {
      JsonElement value = arguments.get(name);
      if (value == null || !value.isJsonArray()) {
         return new int[0];
      }
      JsonArray array = value.getAsJsonArray();
      if (array.size() > maximum) {
         throw new IllegalArgumentException(name + " may contain at most " + maximum + " entries");
      }
      int[] result = new int[array.size()];
      for (int index = 0; index < array.size(); index++) {
         result[index] = array.get(index).getAsInt();
      }
      return result;
   }

   private static String rowState(ResultSetRow row) {
      return switch (row.getState()) {
         case ResultSetRow.STATE_ADDED -> "added";
         case ResultSetRow.STATE_REMOVED -> "removed";
         default -> row.isChanged() ? "changed" : "normal";
      };
   }

   private static Object plainValue(Object value) {
      if (value == null || value instanceof Number || value instanceof Boolean) {
         return value;
      }
      String text = value instanceof String string ? string : String.valueOf(value);
      if (text.length() > MAX_TRANSFER_CELL_CHARS) {
         return text.substring(0, MAX_TRANSFER_CELL_CHARS) + "…[truncated]";
      }
      return text;
   }

   private static long estimateBytes(Object value) {
      if (value == null) {
         return 4L;
      }
      return Math.max(1L, String.valueOf(value).length() * 4L);
   }

   private static String blankToNull(String value) {
      return value == null || value.isBlank() ? null : value;
   }

   private static final class McpResultSetSelection extends StructuredSelection implements IResultSetSelection {
      private final IResultSetController controller;
      private final List<ResultSetRow> rows;

      private McpResultSetSelection(IResultSetController controller, List<ResultSetRow> rows) {
         super(rows);
         this.controller = controller;
         this.rows = List.copyOf(rows);
      }

      @Override
      public IResultSetController getController() {
         return this.controller;
      }

      @Override
      public List<DBDAttributeBinding> getSelectedAttributes() {
         return List.of();
      }

      @Override
      public List<ResultSetRow> getSelectedRows() {
         return this.rows;
      }

      @Override
      public DBDAttributeBinding getElementAttribute(Object element) {
         return null;
      }

      @Override
      public ResultSetRow getElementRow(Object element) {
         return element instanceof ResultSetRow row ? row : null;
      }
   }

   record TransferState(int columns, int loadedRows, String tableName) {
   }

   record TransferSnapshot(
      List<String> columns,
      List<Map<String, Object>> rows,
      String tableName,
      long estimatedBytes,
      boolean truncatedByRows,
      boolean truncatedByBytes
   ) {
      TransferSnapshot {
         columns = List.copyOf(columns);
         rows = List.copyOf(rows);
      }

      boolean truncated() {
         return this.truncatedByRows || this.truncatedByBytes;
      }
   }
}
