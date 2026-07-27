package org.jkiss.dbeaver.teststudio.api;

import com.google.gson.JsonObject;

public interface TestStudioService {
   JsonObject capabilities();
   JsonObject listPlans(String project) throws Exception;
   JsonObject getPlan(String project, String planId) throws Exception;
   JsonObject savePlan(String project, JsonObject plan, boolean overwrite) throws Exception;
   JsonObject listRuns(String project, int limit) throws Exception;
   JsonObject getRun(String project, String runId) throws Exception;
}
