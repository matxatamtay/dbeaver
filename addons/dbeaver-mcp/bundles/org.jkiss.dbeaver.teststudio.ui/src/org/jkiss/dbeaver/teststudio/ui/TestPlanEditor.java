package org.jkiss.dbeaver.teststudio.ui;

import org.eclipse.ui.editors.text.TextEditor;

/** Source-of-truth JSON editor. Structured editing can evolve without changing the plan model. */
public final class TestPlanEditor extends TextEditor {
   public TestPlanEditor() {
      setDocumentProvider(new org.eclipse.ui.editors.text.FileDocumentProvider());
   }
}
