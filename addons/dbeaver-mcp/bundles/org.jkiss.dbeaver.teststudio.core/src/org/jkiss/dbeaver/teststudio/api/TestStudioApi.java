package org.jkiss.dbeaver.teststudio.api;

import java.util.concurrent.atomic.AtomicReference;

public final class TestStudioApi {
   private static final AtomicReference<TestStudioService> SERVICE = new AtomicReference<>();
   private TestStudioApi() { }
   public static TestStudioService get() {
      TestStudioService service = SERVICE.get();
      if (service == null) throw new IllegalStateException("Test Studio core is not initialized");
      return service;
   }
   public static void install(TestStudioService service) {
      if (service == null) throw new IllegalArgumentException("service is required");
      SERVICE.set(service);
   }
   public static void uninstall(TestStudioService service) { SERVICE.compareAndSet(service, null); }
}
