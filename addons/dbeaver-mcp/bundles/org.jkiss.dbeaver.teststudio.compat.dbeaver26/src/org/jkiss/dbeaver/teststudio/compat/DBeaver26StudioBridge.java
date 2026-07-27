package org.jkiss.dbeaver.teststudio.compat;

import com.google.gson.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.teststudio.model.*;
import org.jkiss.dbeaver.teststudio.spi.*;
import org.jkiss.dbeaver.ui.UIUtils;

public final class DBeaver26StudioBridge implements StudioBridge {
   @Override public String id() { return "dbeaver-26"; }

   @Override public JsonObject capabilities() {
      JsonObject result = new JsonObject();
      result.addProperty("dbeaver_line", "26.x");
      result.addProperty("isolated_execution_context", true);
      result.addProperty("transactions", true);
      result.addProperty("savepoints", false);
      result.addProperty("native_confirmation", true);
      result.addProperty("screenshots", true);
      result.addProperty("native_result_grid", false);
      result.addProperty("query_manager_link", false);
      return result;
   }

   @Override public boolean confirm(String title, String message) throws Exception {
      AtomicReference<Boolean> result = new AtomicReference<>(false);
      AtomicReference<Throwable> error = new AtomicReference<>();
      UIUtils.syncExec(() -> {
         try {
            Display display = Display.getDefault();
            Shell shell = workbenchShell(display);
            result.set(MessageDialog.openQuestion(shell, title, message));
         } catch (Throwable e) { error.set(e); }
      });
      if (error.get() instanceof Exception e) throw e;
      if (error.get() != null) throw new RuntimeException(error.get());
      return result.get();
   }

   @Override public StudioSession openSession(StudioTarget target, SandboxStrategy strategy) throws Exception {
      DBPDataSourceContainer container = find(target.connection(), target.project());
      VoidProgressMonitor monitor = new VoidProgressMonitor();
      if (!container.isConnected()) {
         if (!target.autoConnect()) throw new IllegalStateException("Connection is offline and auto_connect=false: " + container.getName());
         if (!container.connect(monitor, true, true)) throw new IllegalStateException("Connection was not established: " + container.getName());
      }
      DBPDataSource dataSource = container.getDataSource();
      if (dataSource == null) throw new IllegalStateException("Connected data source is unavailable: " + container.getName());
      DBCExecutionContext source = DBUtils.getDefaultContext(dataSource, false);
      DBCExecutionContext isolated = dataSource.getDefaultInstance().openIsolatedContext(monitor, "AI Database Test Studio", source);
      DBCSession session = isolated.openSession(monitor, DBCExecutionPurpose.UTIL, "Test Studio run");
      session.enableLogging(false);
      DBCTransactionManager tx = DBUtils.getTransactionManager(isolated);
      boolean requestTransaction = strategy == SandboxStrategy.TRANSACTION || strategy == SandboxStrategy.SAVEPOINT;
      if (requestTransaction) {
         if (tx == null || !tx.isSupportsTransactions()) {
            closeQuietly(session, isolated);
            throw new IllegalStateException("Selected sandbox requires transactions but the connection does not expose a transaction manager");
         }
         if (tx.isAutoCommit()) tx.setAutoCommit(monitor, false);
      }
      return new Session(container, dataSource, isolated, session, tx, requestTransaction);
   }

   @Override public JsonObject captureScreenshot(Path output) throws Exception {
      Files.createDirectories(output.toAbsolutePath().normalize().getParent());
      AtomicReference<JsonObject> result = new AtomicReference<>();
      AtomicReference<Throwable> error = new AtomicReference<>();
      UIUtils.syncExec(() -> {
         Image image = null; GC gc = null;
         try {
            Display display = Display.getDefault();
            Shell shell = workbenchShell(display);
            var bounds = shell.getClientArea();
            image = new Image(display, Math.max(1, bounds.width), Math.max(1, bounds.height));
            gc = new GC(shell);
            gc.copyArea(image, 0, 0);
            ImageLoader loader = new ImageLoader();
            loader.data = new org.eclipse.swt.graphics.ImageData[] { image.getImageData() };
            loader.save(output.toString(), SWT.IMAGE_PNG);
            JsonObject payload = new JsonObject();
            payload.addProperty("supported", true);
            payload.addProperty("path", output.toString());
            payload.addProperty("width", bounds.width);
            payload.addProperty("height", bounds.height);
            result.set(payload);
         } catch (Throwable e) { error.set(e); }
         finally { if (gc != null) gc.dispose(); if (image != null) image.dispose(); }
      });
      if (error.get() instanceof Exception e) throw e;
      if (error.get() != null) throw new RuntimeException(error.get());
      return result.get();
   }

   private static Shell workbenchShell(Display display) {
      if (display == null || display.isDisposed()) {
         throw new IllegalStateException("DBeaver display is unavailable");
      }
      Shell activeShell = display.getActiveShell();
      if (activeShell != null && !activeShell.isDisposed()) {
         return activeShell;
      }
      IWorkbenchWindow activeWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (activeWindow != null && activeWindow.getShell() != null && !activeWindow.getShell().isDisposed()) {
         return activeWindow.getShell();
      }
      for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
         if (window != null && window.getShell() != null && !window.getShell().isDisposed()) {
            return window.getShell();
         }
      }
      throw new IllegalStateException("DBeaver workbench shell is unavailable");
   }

   private static DBPDataSourceContainer find(String connection, String project) {
      List<DBPDataSourceContainer> ids = new ArrayList<>(), names = new ArrayList<>();
      for (DBPDataSourceContainer item : DataSourceRegistry.getAllDataSources()) {
         if (!project.isBlank() && !project.equals(item.getProject().getName())) continue;
         if (connection.equals(item.getId())) ids.add(item);
         if (connection.equals(item.getName())) names.add(item);
      }
      List<DBPDataSourceContainer> matches = ids.isEmpty() ? names : ids;
      if (matches.isEmpty()) throw new IllegalArgumentException("DBeaver connection not found: " + connection);
      if (matches.size() > 1) throw new IllegalArgumentException("Connection is ambiguous; pass project or connection ID: " + connection);
      return matches.getFirst();
   }

   private static void closeQuietly(DBCSession session, DBCExecutionContext context) {
      try { session.close(); } catch (Exception ignored) { }
      try { context.close(); } catch (Exception ignored) { }
   }

   private static final class Session implements StudioSession {
      private final DBPDataSourceContainer container;
      private final DBPDataSource dataSource;
      private final DBCExecutionContext context;
      private final DBCSession session;
      private final DBCTransactionManager tx;
      private final boolean rollbackOnClose;
      private boolean finished;

      Session(DBPDataSourceContainer container, DBPDataSource dataSource, DBCExecutionContext context, DBCSession session, DBCTransactionManager tx, boolean rollbackOnClose) {
         this.container = container; this.dataSource = dataSource; this.context = context; this.session = session; this.tx = tx; this.rollbackOnClose = rollbackOnClose;
      }
      @Override public JsonObject connection() {
         JsonObject result = new JsonObject(); result.addProperty("project", container.getProject().getName()); result.addProperty("id", container.getId()); result.addProperty("name", container.getName()); result.addProperty("driver_id", container.getDriver().getId()); result.addProperty("driver", container.getDriver().getName()); result.addProperty("read_only", container.isConnectionReadOnly()); result.addProperty("credential_fields_included", false); return result;
      }
      @Override public String productName() { return dataSource.getInfo().getDatabaseProductName(); }
      @Override public String driverId() { return container.getDriver().getFullId(); }
      @Override public boolean supportsTransactions() { try { return tx != null && tx.isSupportsTransactions(); } catch (Exception e) { return false; } }
      @Override public boolean transactionActive() throws Exception { return tx != null && tx.isSupportsTransactions() && !tx.isAutoCommit(); }
      @Override public JsonObject execute(String sql, int maxRows, int timeoutSeconds) throws Exception {
         long started = System.nanoTime(); JsonObject payload = new JsonObject(); payload.add("connection", connection()); payload.addProperty("sql_sha256", sha(sql)); payload.addProperty("sql_chars", sql.length());
         try (DBCStatement statement = session.prepareStatement(DBCStatementType.EXEC, sql, false, false, false)) {
            statement.setStatementTimeout(timeoutSeconds); statement.setLimit(0L, maxRows + 1L); boolean hasResult = statement.executeStatement(); payload.addProperty("has_result_set", hasResult);
            if (hasResult) { try (DBCResultSet resultSet = statement.openResultSet()) { read(resultSet, maxRows, payload); } }
            else payload.addProperty("update_count", statement.getUpdateRowCount());
            Throwable[] warnings = statement.getStatementWarnings(); if (warnings != null && warnings.length > 0) { JsonArray items = new JsonArray(); for (Throwable warning : warnings) items.add(safe(warning)); payload.add("warnings", items); }
         }
         payload.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000.0); return payload;
      }
      @Override public void commit() throws Exception { if (tx == null || !tx.isSupportsTransactions()) throw new IllegalStateException("Transactions are unsupported"); tx.commit(session); finished = true; }
      @Override public void rollback() throws Exception { if (tx == null || !tx.isSupportsTransactions()) throw new IllegalStateException("Transactions are unsupported"); tx.rollback(session, null); finished = true; }
      @Override public void close() throws Exception {
         Exception failure = null;
         if (rollbackOnClose && !finished && tx != null && tx.isSupportsTransactions() && !tx.isAutoCommit()) try { tx.rollback(session, null); } catch (Exception e) { failure = e; }
         try { session.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
         try { context.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
         if (failure != null) throw failure;
      }
      private static void read(DBCResultSet rs, int maxRows, JsonObject payload) throws Exception {
         JsonArray columns = new JsonArray(), rows = new JsonArray(); boolean truncated = false;
         if (rs != null) {
            List<? extends DBCAttributeMetaData> attrs = rs.getMeta().getAttributes();
            for (DBCAttributeMetaData a : attrs) { JsonObject c = new JsonObject(); c.addProperty("name", a.getName()); c.addProperty("label", a.getLabel()); c.addProperty("type", a.getTypeName()); c.addProperty("data_kind", a.getDataKind().name()); columns.add(c); }
            while (rs.nextRow()) { if (rows.size() >= maxRows) { truncated = true; break; } JsonObject row = new JsonObject(); for (int i = 0; i < attrs.size(); i++) { String key = unique(row, attrs.get(i).getLabel(), i); row.add(key, value(rs.getAttributeValue(i))); } rows.add(row); }
         }
         payload.add("columns", columns); payload.add("rows", rows); payload.addProperty("row_count", rows.size()); payload.addProperty("truncated", truncated);
      }
      private static String unique(JsonObject row, String label, int index) { String base = label == null || label.isBlank() ? "column_" + (index + 1) : label; if (!row.has(base)) return base; int n = 2; while (row.has(base + "_" + n)) n++; return base + "_" + n; }
      private static JsonElement value(Object value) { if (value == null) return JsonNull.INSTANCE; if (value instanceof Number || value instanceof Boolean) return new Gson().toJsonTree(value); if (value instanceof byte[] bytes) return new JsonPrimitive(Base64.getEncoder().encodeToString(bytes)); String text = String.valueOf(value); return new JsonPrimitive(text.length() <= 65536 ? text : text.substring(0, 65536) + "…[truncated]"); }
      private static String sha(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
      private static String safe(Throwable e) { return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage(); }
   }
}
