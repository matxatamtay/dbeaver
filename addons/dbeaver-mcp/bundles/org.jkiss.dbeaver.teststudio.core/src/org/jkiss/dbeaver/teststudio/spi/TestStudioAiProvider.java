package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonObject;

public interface TestStudioAiProvider {
   String id();
   default int priority() { return 100; }
   JsonObject capabilities();
   JsonObject generatePlan(String prompt, JsonObject context) throws Exception;
   default JsonObject improvePlan(JsonObject plan, String request, JsonObject context) throws Exception {
      throw new UnsupportedOperationException("Plan improvement is unsupported");
   }
   default JsonObject analyzeFailure(JsonObject run, JsonObject context) throws Exception {
      throw new UnsupportedOperationException("Failure analysis is unsupported");
   }
}
