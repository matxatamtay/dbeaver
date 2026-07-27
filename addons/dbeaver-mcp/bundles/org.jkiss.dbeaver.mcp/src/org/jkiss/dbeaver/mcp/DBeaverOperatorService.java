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
import java.time.Instant;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

final class DBeaverOperatorService {
   private final DBeaverConnectionService connections;
   private final DBeaverSqlService sql;
   private final DBeaverEditorService editors;
   private final DBeaverExecutionStore executions;
   private final DBeaverSimulationService simulations;

   DBeaverOperatorService(
      DBeaverConnectionService connections,
      DBeaverSqlService sql,
      DBeaverEditorService editors,
      DBeaverExecutionStore executions,
      DBeaverSimulationService simulations
   ) {
      this.connections = connections;
      this.sql = sql;
      this.editors = editors;
      this.executions = executions;
      this.simulations = simulations;
   }

   JsonObject prepareExecution(JsonObject arguments) throws Exception {
      String editorId = McpJson.getString(arguments, "editor_id", "");
      String sqlText;
      DBPDataSourceContainer container;
      String source;
      if (!editorId.isBlank()) {
         DBeaverEditorService.EditorExecutionTarget target = this.editors.executionTarget(editorId);
         sqlText = target.sql();
         container = target.container();
         source = target.source();
      } else {
         String connectionName = McpJson.requiredString(arguments, "connection");
         String project = McpJson.getString(arguments, "project", "");
         sqlText = McpJson.requiredString(arguments, "sql");
         container = DBeaverConnectionService.findConnection(connectionName, project);
         source = "arguments";
      }
      int maxRows = McpJson.getInt(arguments, "max_rows", DBeaverSqlService.DEFAULT_MAX_ROWS, 1, DBeaverSqlService.MAX_ROWS);
      int timeoutSeconds = McpJson.getInt(
         arguments,
         "timeout_seconds",
         DBeaverSqlService.DEFAULT_TIMEOUT_SECONDS,
         1,
         DBeaverSqlService.MAX_TIMEOUT_SECONDS
      );
      boolean autoConnect = McpJson.getBoolean(arguments, "auto_connect", true);
      boolean readOnly = SqlSafety.isReadOnly(sqlText);
      String summary = "LCA is requesting SQL execution.\n\nConnection: "
         + container.getProject().getName()
         + "/"
         + container.getName()
         + "\nRisk: "
         + (readOnly ? "READ" : "WRITE / DDL")
         + "\nSource: "
         + source
         + "\n\nReview the complete SQL below before approving.";
      if (!confirmSql("Run SQL from LCA?", summary, sqlText, "Run")) {
         JsonObject cancelled = new JsonObject();
         cancelled.addProperty("approved", false);
         cancelled.addProperty("cancelled", true);
         cancelled.addProperty("connection", container.getId());
         cancelled.addProperty("project", container.getProject().getName());
         return cancelled;
      }
      DBeaverExecutionStore.ExecutionRequest request = new DBeaverExecutionStore.ExecutionRequest(
         editorId,
         container.getId(),
         container.getProject().getName(),
         sqlText,
         maxRows,
         timeoutSeconds,
         autoConnect,
         readOnly,
         Instant.now()
      );
      JsonObject approval = this.executions.createApproval(request);
      approval.addProperty("source", source);
      return approval;
   }

   JsonObject executeApproved(JsonObject arguments) throws Exception {
      String approvalId = McpJson.requiredString(arguments, "approval_id");
      DBeaverExecutionStore.ExecutionRequest request = this.executions.consumeApproval(approvalId);
      DBeaverConnectionService.ResolvedConnection connection = this.connections.resolve(
         request.connection(),
         request.project(),
         request.autoConnect()
      );
      DBCExecutionContext context = null;
      if (!request.editorId().isBlank()) {
         try {
            context = this.editors.executionContext(request.editorId());
         } catch (IllegalArgumentException ignored) {
            // The exact SQL and connection remain bound to the approval even if the editor was closed.
         }
      }
      JsonObject result = this.sql.executeApproved(
         connection,
         context,
         request.sql(),
         request.maxRows(),
         request.timeoutSeconds(),
         request.readOnly()
      );
      return this.executions.storeResult(request, result);
   }

   JsonObject cancelExecution(JsonObject arguments) {
      return this.executions.cancelApproval(McpJson.requiredString(arguments, "approval_id"));
   }

   JsonObject getLastResult(JsonObject arguments) {
      return this.executions.lastResult();
   }

   JsonObject fetchResult(JsonObject arguments) {
      String executionId = McpJson.getString(arguments, "execution_id", "");
      int page = McpJson.getInt(arguments, "page", 1, 1, 1000000);
      int pageSize = McpJson.getInt(arguments, "page_size", 100, 1, 200);
      return this.executions.fetchResult(executionId, page, pageSize);
   }

   JsonObject getLastQueries(JsonObject arguments) {
      int limit = McpJson.getInt(arguments, "limit", 20, 1, 100);
      return this.executions.queryHistory(limit);
   }

   JsonObject getTransactionStatus(JsonObject arguments) throws Exception {
      DBCExecutionContext context = requireEditorContext(arguments);
      DBCTransactionManager manager = DBUtils.getTransactionManager(context);
      JsonObject payload = transactionPayload(context, manager);
      payload.addProperty("editor_id", McpJson.requiredString(arguments, "editor_id"));
      return payload;
   }

   JsonObject beginTransaction(JsonObject arguments) throws Exception {
      DBCExecutionContext context = requireEditorContext(arguments);
      DBCTransactionManager manager = requireTransactionManager(context);
      if (manager.isAutoCommit()) {
         manager.setAutoCommit(new VoidProgressMonitor(), false);
      }
      JsonObject payload = transactionPayload(context, manager);
      payload.addProperty("began", true);
      payload.addProperty("editor_id", McpJson.requiredString(arguments, "editor_id"));
      return payload;
   }

   JsonObject commit(JsonObject arguments) throws Exception {
      return finishTransaction(arguments, true);
   }

   JsonObject rollback(JsonObject arguments) throws Exception {
      return finishTransaction(arguments, false);
   }

   JsonObject simulateChange(JsonObject arguments) throws Exception {
      String connection = McpJson.requiredString(arguments, "connection");
      String project = McpJson.getString(arguments, "project", "");
      String sqlText = McpJson.requiredString(arguments, "sql");
      DBPDataSourceContainer container = DBeaverConnectionService.findConnection(connection, project);
      String summary = "LCA is requesting a transactional DML simulation.\n\nConnection: "
         + container.getProject().getName()
         + "/"
         + container.getName()
         + "\n\nDBeaver will roll back the database transaction, but sequence increments, notifications, external calls, files, jobs, and autonomous transactions may remain.";
      if (!confirmSql("Simulate SQL change from LCA?", summary, sqlText, "Simulate and Roll Back")) {
         JsonObject cancelled = new JsonObject();
         cancelled.addProperty("cancelled", true);
         cancelled.addProperty("executed", false);
         return cancelled;
      }
      return this.simulations.simulateChange(arguments);
   }

   private JsonObject finishTransaction(JsonObject arguments, boolean commit) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      DBCExecutionContext context = requireEditorContext(arguments);
      DBCTransactionManager manager = requireTransactionManager(context);
      if (manager.isAutoCommit()) {
         throw new IllegalStateException("The editor is in auto-commit mode; there is no manual transaction to " + (commit ? "commit" : "roll back"));
      }
      String action = commit ? "commit" : "roll back";
      if (!confirm((commit ? "Commit" : "Rollback") + " LCA transaction?", "Editor: " + editorId + "\nConnection: "
         + context.getDataSource().getContainer().getName() + "\n\nDo you want to " + action + " the current transaction?")) {
         JsonObject cancelled = transactionPayload(context, manager);
         cancelled.addProperty("cancelled", true);
         cancelled.addProperty("editor_id", editorId);
         return cancelled;
      }
      try (DBCSession session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, commit ? "MCP commit" : "MCP rollback")) {
         if (commit) {
            manager.commit(session);
         } else {
            manager.rollback(session, null);
         }
      }
      JsonObject payload = transactionPayload(context, manager);
      payload.addProperty(commit ? "committed" : "rolled_back", true);
      payload.addProperty("editor_id", editorId);
      return payload;
   }

   private DBCExecutionContext requireEditorContext(JsonObject arguments) throws Exception {
      String editorId = McpJson.requiredString(arguments, "editor_id");
      DBCExecutionContext context = this.editors.executionContext(editorId);
      if (context == null || !context.isConnected()) {
         throw new IllegalStateException("The SQL editor does not have a connected execution context");
      }
      return context;
   }

   private static DBCTransactionManager requireTransactionManager(DBCExecutionContext context) throws Exception {
      DBCTransactionManager manager = DBUtils.getTransactionManager(context);
      if (manager == null || !manager.isSupportsTransactions()) {
         throw new IllegalStateException("The selected editor connection does not support transactions");
      }
      return manager;
   }

   private static JsonObject transactionPayload(DBCExecutionContext context, DBCTransactionManager manager) throws Exception {
      JsonObject payload = new JsonObject();
      payload.addProperty("context_id", context.getContextId());
      payload.addProperty("context_name", context.getContextName());
      payload.addProperty("connection", context.getDataSource().getContainer().getId());
      payload.addProperty("project", context.getDataSource().getContainer().getProject().getName());
      payload.addProperty("supports_transactions", manager != null && manager.isSupportsTransactions());
      if (manager != null && manager.isSupportsTransactions()) {
         boolean autoCommit = manager.isAutoCommit();
         payload.addProperty("auto_commit", autoCommit);
         payload.addProperty("transaction_active", !autoCommit);
         if (manager.getTransactionIsolation() != null) {
            payload.addProperty("isolation", manager.getTransactionIsolation().getTitle());
         }
      }
      return payload;
   }

   private static boolean confirm(String title, String message) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
         if (window == null) {
            throw new IllegalStateException("DBeaver workbench window is unavailable for user confirmation");
         }
         return MessageDialog.openQuestion(window.getShell(), title, message);
      });
   }

   private static boolean confirmSql(String title, String summary, String sql, String approveLabel) throws Exception {
      return DBeaverEditorService.uiCall(() -> {
         IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
         if (window == null) {
            throw new IllegalStateException("DBeaver workbench window is unavailable for user confirmation");
         }
         return new DBeaverSqlApprovalDialog(window.getShell(), title, summary, sql, approveLabel).open() == Window.OK;
      });
   }
}
