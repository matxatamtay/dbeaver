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

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

final class DBeaverSqlApprovalDialog extends Dialog {
   private final String title;
   private final String summary;
   private final String sql;
   private final String approveLabel;

   DBeaverSqlApprovalDialog(Shell parentShell, String title, String summary, String sql, String approveLabel) {
      super(parentShell);
      this.title = title;
      this.summary = summary;
      this.sql = sql;
      this.approveLabel = approveLabel;
      setShellStyle(getShellStyle() | SWT.RESIZE);
   }

   @Override
   protected void configureShell(Shell shell) {
      super.configureShell(shell);
      shell.setText(this.title);
   }

   @Override
   protected Control createDialogArea(Composite parent) {
      Composite area = (Composite)super.createDialogArea(parent);
      Composite content = new Composite(area, SWT.NONE);
      content.setLayout(new GridLayout(1, false));
      GridDataFactory.fillDefaults().grab(true, true).hint(820, 520).applyTo(content);

      Label summaryLabel = new Label(content, SWT.WRAP);
      summaryLabel.setText(this.summary);
      GridDataFactory.fillDefaults().grab(true, false).hint(780, SWT.DEFAULT).applyTo(summaryLabel);

      Label sqlLabel = new Label(content, SWT.NONE);
      sqlLabel.setText("SQL to execute:");
      GridDataFactory.fillDefaults().applyTo(sqlLabel);

      Text sqlText = new Text(content, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL);
      sqlText.setText(this.sql);
      sqlText.setSelection(0);
      GridDataFactory.fillDefaults().grab(true, true).hint(780, 400).applyTo(sqlText);
      return area;
   }

   @Override
   protected void createButtonsForButtonBar(Composite parent) {
      createButton(parent, IDialogConstants.OK_ID, this.approveLabel, true);
      createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
   }
}
