package org.jkiss.dbeaver.teststudio.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.PlatformUI;

public final class RefreshTestStudioHandler extends AbstractHandler {
   @Override
   public Object execute(ExecutionEvent event) {
      var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window != null && window.getActivePage() != null) {
         var view = window.getActivePage().findView(TestStudioView.ID);
         if (view instanceof TestStudioView studio) studio.refresh();
      }
      return null;
   }
}
