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
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLNavigatorContext;

final class DBeaverEditorService {
   private static final int MAX_DIFF_CHARS = 20000;
   private static final int MAX_SQL_PREVIEW_CHARS = 20000;
   private static final int MAX_DOCUMENT_PREVIEW_CHARS = 4000;
   private final Map<String, WeakReference<SQLEditor>> editors = new ConcurrentHashMap<>();
   private final Map<SQLEditor, String> editorIds = Collections.synchronizedMap(new WeakHashMap<>());

   DBeaverEditorService() {
   }

   JsonObject openEditor(JsonObject arguments) throws Exception {
      String connectionName = McpJson.requiredString(arguments, "connection");
      String project = McpJson.getString(arguments, "project", "");
      String title = McpJson.getString(arguments, "title", "LCA SQL");
      String sql = McpJson.getString(arguments, "sql", "");
      String database = McpJson.getString(arguments, "database", "");
      String schema = McpJson.getString(arguments, "schema", "");
      DBPDataSourceContainer container = DBeaverConnectionService.findConnection(connectionName, project);
      return uiCall(() -> {
         IWorkbenchWindow window = activeWindow();
         SQLEditor editor = SQLEditorHandlerOpenEditor.openSQLConsole(
            window,
            new SQLNavigatorContext(container, null),
            title.isBlank() ? "LCA SQL" : title,
            sql
         );
         if (editor == null) {
            throw new IllegalStateException("DBeaver could not open a SQL editor");
         }
         String editorId = register(editor);
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("opened", true);
         if (!database.isBlank() || !schema.isBlank()) {
            JsonObject requestedContext = new JsonObject();
            requestedContext.addProperty("database", database);
            requestedContext.addProperty("schema", schema);
            payload.add("requested_context", requestedContext);
            payload.addProperty(
               "context_note",
               "Database/schema switching remains controlled by DBeaver connection defaults; the requested context is preserved for the operator workflow."
            );
         }
         return payload;
      });
   }

   JsonObject insertSql(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String sql = McpJson.requiredString(arguments, "sql");
      return mutateDocument(editorId, "insert", (editor, document, selection) -> {
         int offset = selection == null ? document.getLength() : selection.getOffset();
         int length = selection == null ? 0 : selection.getLength();
         document.replace(offset, length, sql);
         editor.getSelectionProvider().setSelection(new TextSelection(offset + sql.length(), 0));
      });
   }

   JsonObject replaceSql(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String sql = McpJson.getString(arguments, "sql", "");
      boolean selectAll = McpJson.getBoolean(arguments, "select_all", true);
      return mutateDocument(editorId, "replace", (editor, document, selection) -> {
         document.set(sql);
         editor.getSelectionProvider().setSelection(selectAll ? new TextSelection(0, sql.length()) : new TextSelection(sql.length(), 0));
      });
   }

   JsonObject appendSql(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String sql = McpJson.requiredString(arguments, "sql");
      return mutateDocument(editorId, "append", (editor, document, selection) -> {
         String prefix = document.getLength() == 0 || document.get().endsWith("\n") ? "" : System.lineSeparator();
         int offset = document.getLength();
         String addition = prefix + sql;
         document.replace(offset, 0, addition);
         editor.getSelectionProvider().setSelection(new TextSelection(offset + addition.length(), 0));
      });
   }

   JsonObject focusEditor(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      return uiCall(() -> {
         SQLEditor editor = resolveEditor(editorId);
         IWorkbenchPage page = activeWindow().getActivePage();
         page.activate(editor);
         editor.setFocus();
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("focused", true);
         return payload;
      });
   }

   JsonObject saveSqlSnippet(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      return uiCall(() -> {
         SQLEditor editor = resolveEditor(editorId);
         activeWindow().getActivePage().activate(editor);
         editor.doSaveAs();
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("save_dialog_opened", true);
         return payload;
      });
   }

   JsonObject selectConnection(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      String connectionName = McpJson.requiredString(arguments, "connection");
      String project = McpJson.getString(arguments, "project", "");
      DBPDataSourceContainer container = DBeaverConnectionService.findConnection(connectionName, project);
      return uiCall(() -> {
         SQLEditor editor = resolveEditor(editorId);
         editor.setDataSourceContainer(container);
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("connection_changed", true);
         return payload;
      });
   }

   JsonObject getActiveEditor(JsonObject arguments) throws Exception {
      return uiCall(() -> {
         SQLEditor editor = activeSqlEditor();
         if (editor == null) {
            JsonObject payload = new JsonObject();
            payload.addProperty("active", false);
            return payload;
         }
         String editorId = register(editor);
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("active", true);
         return payload;
      });
   }

   JsonObject getCurrentSelection(JsonObject arguments) throws Exception {
      String requestedId = McpJson.getString(arguments, "editor_id", "");
      return uiCall(() -> {
         SQLEditor editor = requestedId.isBlank() ? activeSqlEditor() : resolveEditor(requestedId);
         if (editor == null) {
            throw new IllegalStateException("No active SQL editor");
         }
         String editorId = register(editor);
         IDocument document = requireDocument(editor);
         ITextSelection selection = textSelection(editor);
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("offset", selection == null ? 0 : selection.getOffset());
         payload.addProperty("length", selection == null ? 0 : selection.getLength());
         addBoundedText(payload, "sql", selection == null ? "" : selection.getText(), MAX_SQL_PREVIEW_CHARS);
         SQLScriptElement active = editor.extractActiveQuery();
         addBoundedText(payload, "active_statement", active == null ? "" : active.getText(), MAX_SQL_PREVIEW_CHARS);
         payload.addProperty("document_length", document.getLength());
         return payload;
      });
   }

   JsonObject proposeSql(JsonObject arguments) throws Exception {
      String editorId = McpJson.getString(arguments, "editor_id", "");
      if (editorId.isBlank()) {
         JsonObject active = getActiveEditor(new JsonObject());
         if (McpJson.getBoolean(active, "active", false)) {
            editorId = McpJson.requiredString(active, "editor_id");
         } else {
            editorId = McpJson.requiredString(openEditor(arguments), "editor_id");
         }
      }
      String sql = McpJson.requiredString(arguments, "sql");
      String finalEditorId = editorId;
      return uiCall(() -> {
         SQLEditor editor = resolveEditor(finalEditorId);
         IDocument document = requireDocument(editor);
         String previous = document.get();
         document.set(sql);
         editor.getSelectionProvider().setSelection(new TextSelection(0, sql.length()));
         activeWindow().getActivePage().activate(editor);
         editor.setFocus();
         JsonObject payload = editorPayload(finalEditorId, editor);
         payload.addProperty("proposal_id", UUID.randomUUID().toString());
         payload.addProperty("artifact_type", "sql");
         addBoundedText(payload, "sql", sql, MAX_SQL_PREVIEW_CHARS);
         addBoundedText(payload, "previous_sql", previous, MAX_DOCUMENT_PREVIEW_CHARS);
         payload.addProperty("diff", buildDiff(previous, sql));
         payload.addProperty("selected_all", true);
         payload.addProperty("requires_execution_approval", true);
         JsonObject actions = new JsonObject();
         actions.addProperty("run", "dbeaver_prepare_sql_execution");
         actions.addProperty("explain", "dbeaver_explain_query");
         actions.addProperty("save_snippet", "dbeaver_save_sql_snippet");
         payload.add("actions", actions);
         return payload;
      });
   }

   EditorExecutionTarget executionTarget(String editorId) throws Exception {
      return uiCall(() -> {
         SQLEditor editor = resolveEditor(editorId);
         IDocument document = requireDocument(editor);
         ITextSelection selection = textSelection(editor);
         String sql;
         String source;
         if (selection != null && selection.getLength() > 0) {
            sql = selection.getText();
            source = "selection";
         } else {
            SQLScriptElement active = editor.extractActiveQuery();
            if (active != null && !active.getText().isBlank()) {
               sql = active.getText();
               source = "active_statement";
            } else {
               sql = document.get();
               source = "document";
            }
         }
         if (sql.isBlank()) {
            throw new IllegalArgumentException("The SQL editor does not contain an executable statement");
         }
         DBPDataSourceContainer container = editor.getDataSourceContainer();
         if (container == null) {
            throw new IllegalStateException("The SQL editor has no selected connection");
         }
         return new EditorExecutionTarget(editorId, container, sql, source);
      });
   }

   DBCExecutionContext executionContext(String editorId) throws Exception {
      return uiCall(() -> resolveEditor(editorId).getExecutionContext());
   }

   private JsonObject mutateDocument(String editorId, String operation, DocumentMutation mutation) throws Exception {
      return uiCall(() -> {
         SQLEditor editor = resolveEditor(editorId);
         IDocument document = requireDocument(editor);
         mutation.apply(editor, document, textSelection(editor));
         JsonObject payload = editorPayload(editorId, editor);
         payload.addProperty("operation", operation);
         payload.addProperty("document_length", document.getLength());
         addBoundedText(payload, "sql", document.get(), MAX_DOCUMENT_PREVIEW_CHARS);
         return payload;
      });
   }

   private String register(SQLEditor editor) {
      String existing = this.editorIds.get(editor);
      if (existing != null) {
         return existing;
      }
      String editorId = UUID.randomUUID().toString();
      this.editorIds.put(editor, editorId);
      this.editors.put(editorId, new WeakReference<>(editor));
      return editorId;
   }

   private SQLEditor resolveEditor(String editorId) {
      WeakReference<SQLEditor> reference = this.editors.get(editorId);
      SQLEditor editor = reference == null ? null : reference.get();
      if (editor == null) {
         this.editors.remove(editorId);
         throw new IllegalArgumentException("SQL editor not found or already closed: " + editorId);
      }
      return editor;
   }

   private static JsonObject editorPayload(String editorId, SQLEditor editor) {
      JsonObject payload = new JsonObject();
      payload.addProperty("editor_id", editorId);
      payload.addProperty("title", editor.getTitle());
      DBPDataSourceContainer container = editor.getDataSourceContainer();
      if (container != null) {
         payload.add("connection", DBeaverConnectionService.connectionPayload(container));
      }
      IDocument document = editor.getDocument();
      payload.addProperty("document_length", document == null ? 0 : document.getLength());
      return payload;
   }

   private static IDocument requireDocument(SQLEditor editor) {
      IDocument document = editor.getDocument();
      if (document == null) {
         throw new IllegalStateException("SQL editor document is unavailable");
      }
      return document;
   }

   private static ITextSelection textSelection(SQLEditor editor) {
      ISelection selection = editor.getSelectionProvider().getSelection();
      return selection instanceof ITextSelection textSelection ? textSelection : null;
   }

   private static SQLEditor activeSqlEditor() {
      IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window == null || window.getActivePage() == null) {
         return null;
      }
      IEditorPart activeEditor = window.getActivePage().getActiveEditor();
      return activeEditor instanceof SQLEditor sqlEditor ? sqlEditor : null;
   }

   private static IWorkbenchWindow activeWindow() {
      IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window == null || window.getActivePage() == null) {
         throw new IllegalStateException("DBeaver workbench window is unavailable");
      }
      return window;
   }

   static <T> T uiCall(Callable<T> callable) throws Exception {
      AtomicReference<T> result = new AtomicReference<>();
      AtomicReference<Throwable> error = new AtomicReference<>();
      UIUtils.syncExec(() -> {
         try {
            result.set(callable.call());
         } catch (Throwable throwable) {
            error.set(throwable);
         }
      });
      Throwable throwable = error.get();
      if (throwable instanceof Exception exception) {
         throw exception;
      }
      if (throwable != null) {
         throw new RuntimeException(throwable);
      }
      return result.get();
   }

   private static void addBoundedText(JsonObject payload, String key, String value, int maxChars) {
      boolean truncated = value.length() > maxChars;
      payload.addProperty(key, truncated ? value.substring(0, maxChars) + "\u2026[truncated]" : value);
      payload.addProperty(key + "_chars", value.length());
      payload.addProperty(key + "_truncated", truncated);
   }

   private static String buildDiff(String previous, String next) {
      if (previous.equals(next)) {
         return "No changes";
      }
      String diff = "--- current.sql\n+++ proposed.sql\n-" + previous.replace("\n", "\n-") + "\n+" + next.replace("\n", "\n+");
      return diff.length() <= MAX_DIFF_CHARS ? diff : diff.substring(0, MAX_DIFF_CHARS) + "\n...[diff truncated]";
   }

   @FunctionalInterface
   private interface DocumentMutation {
      void apply(SQLEditor editor, IDocument document, ITextSelection selection) throws BadLocationException;
   }

   record EditorExecutionTarget(
      String editorId,
      DBPDataSourceContainer container,
      String sql,
      String source
   ) {
   }
}
