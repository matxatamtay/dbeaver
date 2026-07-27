package org.jkiss.dbeaver.teststudio.core;

import org.jkiss.dbeaver.teststudio.api.TestStudioApi;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public final class TestStudioActivator implements BundleActivator {
   private TestStudioRuntime runtime;

   @Override
   public void start(BundleContext context) {
      runtime = new TestStudioRuntime(null);
      TestStudioApi.install(runtime);
   }

   @Override
   public void stop(BundleContext context) {
      TestStudioApi.uninstall(runtime);
      runtime = null;
   }
}
