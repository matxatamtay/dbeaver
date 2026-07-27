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

import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public final class McpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
   public static final String PAGE_ID = "org.jkiss.dbeaver.preferences.mcp";
   private Composite root;
   private Label statusValue;
   private Label endpointValue;
   private Label lastErrorValue;
   private Button toggleButton;
   private Button autoStartCheck;
   private Spinner portSpinner;
   private Text authTokenText;
   private Button showTokenCheck;
   private Text logPathText;

   public McpPreferencePage() {
      this.setDescription("Control the loopback-only MCP server embedded in DBeaver Desktop.");
   }

   public void init(IWorkbench workbench) {
      McpPreferences.initializeDefaults();
   }

   protected Control createContents(Composite parent) {
      McpPreferences.Config stored = McpPreferences.storedConfig();
      this.root = new Composite(parent, 0);
      this.root.setLayout(new GridLayout(1, false));
      this.root.setLayoutData(new GridData(4, 4, true, true));
      this.createStatusGroup(this.root);
      this.createSettingsGroup(this.root, stored);
      this.createLogGroup(this.root);
      this.createOverrideNotice(this.root);
      Dialog.applyDialogFont(this.root);
      this.refreshStatus();
      this.scheduleStatusRefresh();
      return this.root;
   }

   private void createStatusGroup(Composite parent) {
      Group group = new Group(parent, 0);
      group.setText("Server status");
      group.setLayout(new GridLayout(2, false));
      group.setLayoutData(new GridData(4, 128, true, false));
      createLabel(group, "Status:");
      this.statusValue = createValueLabel(group);
      createLabel(group, "Endpoint:");
      this.endpointValue = createValueLabel(group);
      createLabel(group, "Last error:");
      this.lastErrorValue = createValueLabel(group);
      Composite buttons = new Composite(group, 0);
      buttons.setLayout(new GridLayout(2, false));
      buttons.setLayoutData(new GridData(16384, 16777216, false, false, 2, 1));
      this.toggleButton = new Button(buttons, 8);
      this.toggleButton.addListener(13, event -> this.toggleServer());
      Button refreshButton = new Button(buttons, 8);
      refreshButton.setText("Refresh status");
      refreshButton.addListener(13, event -> this.refreshStatus());
   }

   private void createSettingsGroup(Composite parent, McpPreferences.Config stored) {
      Group group = new Group(parent, 0);
      group.setText("Configuration");
      group.setLayout(new GridLayout(3, false));
      group.setLayoutData(new GridData(4, 128, true, false));
      this.autoStartCheck = new Button(group, 32);
      this.autoStartCheck.setText("Start automatically with DBeaver");
      this.autoStartCheck.setSelection(stored.autoStart());
      this.autoStartCheck.setLayoutData(new GridData(16384, 16777216, false, false, 3, 1));
      this.autoStartCheck.setEnabled(!McpPreferences.isEnabledExternallyOverridden());
      createLabel(group, "Port:");
      this.portSpinner = new Spinner(group, 2048);
      this.portSpinner.setMinimum(1);
      this.portSpinner.setMaximum(65535);
      this.portSpinner.setSelection(stored.port());
      this.portSpinner.setEnabled(!McpPreferences.isPortExternallyOverridden());
      this.portSpinner.setLayoutData(new GridData(16384, 16777216, false, false, 2, 1));
      createLabel(group, "Bearer token:");
      this.authTokenText = new Text(group, 4196352);
      this.authTokenText.setText(stored.authToken());
      this.authTokenText.setEnabled(!McpPreferences.isAuthTokenExternallyOverridden());
      this.authTokenText.setLayoutData(new GridData(4, 16777216, true, false));
      this.showTokenCheck = new Button(group, 32);
      this.showTokenCheck.setText("Show");
      this.showTokenCheck.setEnabled(this.authTokenText.isEnabled());
      this.showTokenCheck.addListener(13, event -> this.authTokenText.setEchoChar((char)(this.showTokenCheck.getSelection() ? '\u0000' : '*')));
      Label hint = new Label(group, 64);
      hint.setText("Apply or OK restarts a running server when the port or token changes. The server only binds to the loopback interface.");
      hint.setLayoutData(new GridData(4, 128, true, false, 3, 1));
   }

   private void createLogGroup(Composite parent) {
      Group group = new Group(parent, 0);
      group.setText("MCP log");
      group.setLayout(new GridLayout(3, false));
      group.setLayoutData(new GridData(4, 4, true, true));
      createLabel(group, "Log file:");
      this.logPathText = new Text(group, 2056);
      Path logPath = McpLog.getLogPath();
      this.logPathText.setText(logPath == null ? "In-memory log only" : logPath.toString());
      this.logPathText.setLayoutData(new GridData(4, 16777216, true, false));
      Button openFileButton = new Button(group, 8);
      openFileButton.setText("Open file");
      openFileButton.setEnabled(logPath != null);
      openFileButton.addListener(13, event -> this.openLogFile());
      Composite buttons = new Composite(group, 0);
      buttons.setLayout(new GridLayout(2, false));
      buttons.setLayoutData(new GridData(16384, 16777216, false, false, 3, 1));
      Button viewLogButton = new Button(buttons, 8);
      viewLogButton.setText("View MCP Log");
      viewLogButton.addListener(13, event -> new McpLogDialog(this.getShell()).open());
      Button clearLogButton = new Button(buttons, 8);
      clearLogButton.setText("Clear MCP Log");
      clearLogButton.addListener(13, event -> {
         if (MessageDialog.openQuestion(this.getShell(), "Clear MCP Log", "Clear the MCP log buffer and log file?")) {
            McpLog.clear();
         }
      });
   }

   private void createOverrideNotice(Composite parent) {
      if (McpPreferences.hasExternalOverrides()) {
         Label notice = new Label(parent, 64);
         notice.setText("Some values are controlled by process settings and cannot be changed here: " + McpPreferences.externalOverrideDescription());
         notice.setLayoutData(new GridData(4, 128, true, false));
      }
   }

   private void toggleServer() {
      DBeaverMcpServer.ServerStatus status = DBeaverMcpServer.status();
      if (status.running()) {
         DBeaverMcpServer.stop();
      } else {
         if (!this.saveSettings()) {
            return;
         }

         DBeaverMcpServer.start();
      }

      this.refreshStatus();
   }

   private boolean saveSettings() {
      try {
         McpPreferences.save(this.autoStartCheck.getSelection(), this.portSpinner.getSelection(), this.authTokenText.getText());
         return true;
      } catch (IOException var2) {
         McpLog.error("Unable to save MCP preferences", var2);
         MessageDialog.openError(this.getShell(), "MCP Server", "Unable to save MCP preferences: " + McpJson.safeMessage(var2));
         return false;
      }
   }

   public boolean performOk() {
      DBeaverMcpServer.ServerStatus before = DBeaverMcpServer.status();
      McpPreferences.Config oldConfig = McpPreferences.effectiveConfig();
      if (!this.saveSettings()) {
         return false;
      } else {
         McpPreferences.Config newConfig = McpPreferences.effectiveConfig();
         if (before.running() && (oldConfig.port() != newConfig.port() || !oldConfig.authToken().equals(newConfig.authToken()))) {
            DBeaverMcpServer.restart();
         }

         return true;
      }
   }

   protected void performApply() {
      this.performOk();
      this.refreshStatus();
   }

   protected void performDefaults() {
      this.autoStartCheck.setSelection(true);
      this.portSpinner.setSelection(3846);
      this.authTokenText.setText("");
      super.performDefaults();
   }

   private void refreshStatus() {
      if (this.root != null && !this.root.isDisposed()) {
         DBeaverMcpServer.ServerStatus status = DBeaverMcpServer.status();
         this.statusValue.setText(status.running() ? "Running" : "Stopped");
         this.endpointValue.setText(status.endpoint().isBlank() ? "Not listening" : status.endpoint());
         this.lastErrorValue.setText(status.lastError().isBlank() ? "None" : status.lastError());
         this.toggleButton.setText(status.running() ? "Stop MCP Server" : "Start MCP Server");
         this.root.layout(true, true);
      }
   }

   private void scheduleStatusRefresh() {
      if (this.root != null && !this.root.isDisposed()) {
         this.root.getDisplay().timerExec(1000, () -> {
            if (this.root != null && !this.root.isDisposed()) {
               this.refreshStatus();
               this.scheduleStatusRefresh();
            }
         });
      }
   }

   private void openLogFile() {
      Path path = McpLog.getLogPath();
      if (path == null || !Program.launch(path.toString())) {
         MessageDialog.openInformation(this.getShell(), "MCP Log", path == null ? "The MCP log currently exists only in memory." : "Log file: " + path);
      }
   }

   private static Label createLabel(Composite parent, String text) {
      Label label = new Label(parent, 0);
      label.setText(text);
      return label;
   }

   private static Label createValueLabel(Composite parent) {
      Label label = new Label(parent, 64);
      label.setLayoutData(new GridData(4, 16777216, true, false));
      return label;
   }
}
