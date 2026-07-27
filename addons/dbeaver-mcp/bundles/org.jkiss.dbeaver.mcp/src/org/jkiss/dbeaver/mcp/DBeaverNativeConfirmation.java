/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

final class DBeaverNativeConfirmation {
   private DBeaverNativeConfirmation() {
   }

   static boolean confirm(String title, String message) throws Exception {
      return DBeaverEditorService.uiCall(() -> MessageDialog.openQuestion(requireWindow().getShell(), title, message));
   }

   static boolean confirmSql(String title, String summary, String sql, String approveLabel) throws Exception {
      return DBeaverEditorService.uiCall(() ->
         new DBeaverSqlApprovalDialog(requireWindow().getShell(), title, summary, sql, approveLabel).open() == Window.OK);
   }

   private static IWorkbenchWindow requireWindow() {
      IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
      if (window == null) {
         throw new IllegalStateException("DBeaver workbench window is unavailable for user confirmation");
      }
      return window;
   }
}
