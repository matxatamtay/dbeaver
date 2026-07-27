package org.jkiss.dbeaver.teststudio.ui;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.core.resources.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.teststudio.api.TestStudioApi;

public final class TestStudioView extends ViewPart {
   public static final String ID = "org.jkiss.dbeaver.teststudio.ui.view";
   private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
   private TreeViewer viewer;
   private Text details;
   private Label status;

   @Override
   public void createPartControl(Composite parent) {
      parent.setLayout(new GridLayout(1, false));
      status = new Label(parent, SWT.NONE);
      status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
      SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
      sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
      viewer = new TreeViewer(sash, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
      viewer.setContentProvider(new StudioContentProvider());
      viewer.setLabelProvider(new StudioLabelProvider());
      details = new Text(sash, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
      sash.setWeights(40, 60);
      viewer.addSelectionChangedListener(event -> showSelection());
      viewer.addDoubleClickListener(event -> openSelection());
      refresh();
   }

   public void refresh() {
      if (viewer == null || viewer.getControl().isDisposed()) return;
      List<Node> roots = new ArrayList<>();
      int planCount = 0, runCount = 0;
      for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
         if (!project.isOpen()) continue;
         Node projectNode = new Node(Kind.PROJECT, project.getName(), project.getName(), new JsonObject());
         Node plans = new Node(Kind.GROUP, "Plans", project.getName(), new JsonObject());
         Node runs = new Node(Kind.GROUP, "Recent Runs", project.getName(), new JsonObject());
         try {
            JsonObject list = TestStudioApi.get().listPlans(project.getName());
            for (JsonElement item : array(list, "plans")) {
               JsonObject payload = item.getAsJsonObject();
               plans.children.add(new Node(
                  Kind.PLAN,
                  string(payload, "name", string(payload, "id", "Plan")),
                  project.getName(),
                  payload.deepCopy()
               ));
               planCount++;
            }
         } catch (Exception e) {
            plans.children.add(error(project.getName(), e));
         }
         try {
            JsonObject list = TestStudioApi.get().listRuns(project.getName(), 100);
            for (JsonElement item : array(list, "runs")) {
               JsonObject payload = item.getAsJsonObject();
               String label = string(payload, "plan_id", "Run") + " — " + string(payload, "state", "unknown");
               runs.children.add(new Node(Kind.RUN, label, project.getName(), payload.deepCopy()));
               runCount++;
            }
         } catch (Exception e) {
            runs.children.add(error(project.getName(), e));
         }
         projectNode.children.add(plans);
         projectNode.children.add(runs);
         roots.add(projectNode);
      }
      viewer.setInput(roots);
      viewer.expandToLevel(2);
      status.setText("AI Database Test Studio — " + planCount + " plans, " + runCount + " recent runs");
   }

   private void showSelection() {
      IStructuredSelection selection = viewer.getStructuredSelection();
      if (!(selection.getFirstElement() instanceof Node node)) {
         details.setText("");
         return;
      }
      JsonObject output = node.payload.deepCopy();
      output.addProperty("kind", node.kind.name().toLowerCase(Locale.ENGLISH));
      output.addProperty("project", node.project);
      if (node.kind == Kind.RUN && node.payload.has("run_id")) {
         try {
            output = TestStudioApi.get().getRun(node.project, node.payload.get("run_id").getAsString());
         } catch (Exception e) {
            output.addProperty("detail_error", safe(e));
         }
      }
      details.setText(gson.toJson(output));
   }

   private void openSelection() {
      IStructuredSelection selection = viewer.getStructuredSelection();
      if (!(selection.getFirstElement() instanceof Node node) || node.kind != Kind.PLAN) return;
      String path = string(node.payload, "path", "");
      if (path.isBlank()) return;
      try {
         IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(node.project);
         IFile file = project.getFile(path);
         if (file.exists()) IDE.openEditor(getSite().getPage(), file, "org.jkiss.dbeaver.teststudio.ui.planEditor", true);
      } catch (Exception e) {
         details.setText("Unable to open test plan:\n" + safe(e));
      }
   }

   @Override
   public void setFocus() {
      if (viewer != null) viewer.getControl().setFocus();
   }

   private static JsonArray array(JsonObject object, String name) {
      return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
   }

   private static String string(JsonObject object, String name, String fallback) {
      return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : fallback;
   }

   private static String safe(Throwable e) {
      return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
   }

   private static Node error(String project, Throwable e) {
      JsonObject payload = new JsonObject();
      payload.addProperty("error", safe(e));
      return new Node(Kind.ERROR, "Error: " + safe(e), project, payload);
   }

   private enum Kind { PROJECT, GROUP, PLAN, RUN, ERROR }

   private static final class Node {
      final Kind kind;
      final String label;
      final String project;
      final JsonObject payload;
      final List<Node> children = new ArrayList<>();

      Node(Kind kind, String label, String project, JsonObject payload) {
         this.kind = kind;
         this.label = label;
         this.project = project;
         this.payload = payload;
      }
   }

   private static final class StudioContentProvider implements ITreeContentProvider {
      @Override public Object[] getElements(Object inputElement) { return inputElement instanceof List<?> list ? list.toArray() : new Object[0]; }
      @Override public Object[] getChildren(Object parentElement) { return parentElement instanceof Node node ? node.children.toArray() : new Object[0]; }
      @Override public Object getParent(Object element) { return null; }
      @Override public boolean hasChildren(Object element) { return element instanceof Node node && !node.children.isEmpty(); }
   }

   private static final class StudioLabelProvider extends LabelProvider {
      @Override public String getText(Object element) { return element instanceof Node node ? node.label : super.getText(element); }
   }
}
