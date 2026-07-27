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

import java.nio.file.Path;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

final class McpLogDialog extends Dialog {
   private static final int REFRESH_ID = 1001;
   private static final int CLEAR_ID = 1002;
   private static final int COPY_ID = 1003;
   private static final int OPEN_FILE_ID = 1004;
   private Text logText;
   private String displayedText = "";

   McpLogDialog(Shell parentShell) {
      super(parentShell);
      this.setShellStyle(this.getShellStyle() | 16 | 1024);
   }

   protected void configureShell(Shell shell) {
      super.configureShell(shell);
      shell.setText("DBeaver MCP Log");
   }

   protected Control createDialogArea(Composite parent) {
      Composite area = (Composite)super.createDialogArea(parent);
      this.logText = new Text(area, 2826);
      this.logText.setLayoutData(new GridData(4, 4, true, true));
      this.refreshText();
      this.scheduleRefresh();
      return area;
   }

   protected void createButtonsForButtonBar(Composite parent) {
      this.createButton(parent, 1001, "Refresh", false);
      this.createButton(parent, 1002, "Clear", false);
      this.createButton(parent, 1003, "Copy all", false);
      this.createButton(parent, 1004, "Open file", false).setEnabled(McpLog.getLogPath() != null);
      this.createButton(parent, 12, IDialogConstants.CLOSE_LABEL, true);
   }

   protected void buttonPressed(int buttonId) {
      switch (buttonId) {
         case 12:
            this.close();
            break;
         case 1001:
            this.refreshText();
            break;
         case 1002:
            this.clearLog();
            break;
         case 1003:
            this.copyAll();
            break;
         case 1004:
            this.openFile();
            break;
         default:
            super.buttonPressed(buttonId);
      }
   }

   protected Point getInitialSize() {
      return new Point(900, 560);
   }

   private void refreshText() {
      if (this.logText != null && !this.logText.isDisposed()) {
         String text = McpLog.getText();
         if (!text.equals(this.displayedText)) {
            this.displayedText = text;
            this.logText.setText(text);
            this.logText.setSelection(this.logText.getCharCount());
            this.logText.showSelection();
         }
      }
   }

   private void scheduleRefresh() {
      if (this.logText != null && !this.logText.isDisposed()) {
         this.logText.getDisplay().timerExec(1000, () -> {
            if (this.logText != null && !this.logText.isDisposed()) {
               this.refreshText();
               this.scheduleRefresh();
            }
         });
      }
   }

   private void clearLog() {
      if (MessageDialog.openQuestion(this.getShell(), "Clear MCP Log", "Clear the MCP log buffer and log file?")) {
         McpLog.clear();
         this.displayedText = "";
         this.refreshText();
      }
   }

   private void copyAll() {
      Clipboard clipboard = new Clipboard(this.getShell().getDisplay());

      try {
         clipboard.setContents(new Object[]{McpLog.getText()}, new Transfer[]{TextTransfer.getInstance()});
      } finally {
         clipboard.dispose();
      }
   }

   private void openFile() {
      Path path = McpLog.getLogPath();
      if (path == null || !Program.launch(path.toString())) {
         MessageDialog.openInformation(this.getShell(), "MCP Log", path == null ? "The MCP log currently exists only in memory." : "Log file: " + path);
      }
   }
}
