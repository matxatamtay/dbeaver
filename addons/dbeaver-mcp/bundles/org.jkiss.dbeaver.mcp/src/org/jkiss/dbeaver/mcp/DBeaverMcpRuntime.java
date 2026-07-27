/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.jkiss.dbeaver.Log;

final class DBeaverMcpRuntime implements AutoCloseable {
   static final String TOOL_PROVIDER_EXTENSION_ID = "org.jkiss.dbeaver.mcp.toolProvider";

   private static final Log log = Log.getLog(DBeaverMcpRuntime.class);

   private final McpToolRegistry tools;
   private final DBeaverMcpContext context;

   private DBeaverMcpRuntime(McpToolRegistry tools, DBeaverMcpContext context) {
      this.tools = tools;
      this.context = context;
   }

   static DBeaverMcpRuntime create(int port, boolean authRequired) {
      DBeaverMcpPolicy policy = DBeaverMcpPolicy.fromEnvironment();
      DBeaverMcpJobManager jobs = new DBeaverMcpJobManager();
      McpToolRegistry registry = new McpToolRegistry(policy);
      DBeaverMcpContext context = new DBeaverMcpContext(port, authRequired, jobs, policy, registry);
      List<ProviderContribution> providers = new ArrayList<>();
      providers.add(new ProviderContribution(new LegacyToolProvider(registry), 0, "builtin"));
      providers.add(new ProviderContribution(new CompactToolProvider(registry), 10, "builtin"));
      providers.add(new ProviderContribution(new JobToolProvider(), 20, "builtin"));
      providers.add(new ProviderContribution(new DataWorkflowToolProvider(), 30, "builtin"));
      providers.add(new ProviderContribution(new Phase3ToolProvider(), 40, "builtin"));
      providers.add(new ProviderContribution(new TesterToolProvider(registry), 50, "builtin"));
      providers.add(new ProviderContribution(new CoverageToolProvider(registry), 60, "builtin"));
      providers.addAll(loadExtensions());
      providers.sort(Comparator.comparingInt(ProviderContribution::priority).thenComparing(item -> item.provider().id()));

      for (ProviderContribution contribution : providers) {
         DBeaverMcpToolProvider provider = contribution.provider();
         try {
            registry.registerProvider(provider, context);
            McpLog.info("Registered MCP tool provider " + provider.id() + " from " + contribution.source());
         } catch (Exception e) {
            log.error("Unable to register DBeaver MCP tool provider " + provider.id(), e);
            McpLog.error("Unable to register MCP tool provider " + provider.id(), e);
         }
      }

      McpLog.info("DBeaver MCP registry contains " + registry.size() + " tools");
      return new DBeaverMcpRuntime(registry, context);
   }

   McpToolRegistry tools() {
      return this.tools;
   }

   DBeaverMcpContext context() {
      return this.context;
   }

   @Override
   public void close() {
      this.context.jobs().close();
   }

   private static List<ProviderContribution> loadExtensions() {
      List<ProviderContribution> result = new ArrayList<>();
      if (Platform.getExtensionRegistry() == null) {
         return result;
      }
      for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(TOOL_PROVIDER_EXTENSION_ID)) {
         try {
            Object instance = element.createExecutableExtension("class");
            if (!(instance instanceof DBeaverMcpToolProvider provider)) {
               McpLog.warn("Ignored MCP tool provider that does not implement DBeaverMcpToolProvider: " + element.getContributor().getName());
               continue;
            }
            int priority = provider.priority();
            String configuredPriority = element.getAttribute("priority");
            if (configuredPriority != null && !configuredPriority.isBlank()) {
               priority = Integer.parseInt(configuredPriority);
            }
            result.add(new ProviderContribution(provider, priority, element.getContributor().getName()));
         } catch (CoreException | NumberFormatException e) {
            log.error("Unable to load DBeaver MCP tool provider extension", e);
            McpLog.error("Unable to load MCP tool provider extension", e);
         }
      }
      return result;
   }

   private record ProviderContribution(DBeaverMcpToolProvider provider, int priority, String source) {
   }
}
