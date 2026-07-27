package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;

final class TestPlanMigrator {
   JsonObject migrate(JsonObject source) {
      JsonObject plan=source.deepCopy(); String from=StudioJson.string(plan,"schema_version","0.9"); JsonArray changes=new JsonArray();
      if (from.equals("0.9") || from.isBlank()) {
         if (plan.has("cases") && !plan.has("steps")) { plan.add("steps",plan.remove("cases")); changes.add("renamed cases to steps"); }
         if (!plan.has("targets")) { JsonObject targets=new JsonObject(); JsonObject target=new JsonObject(); if(plan.has("connection"))target.add("connection",plan.remove("connection")); if(plan.has("project"))target.add("project",plan.remove("project")); if(!target.has("connection"))target.addProperty("connection","CHANGE_ME"); targets.add("default",target); plan.add("targets",targets); changes.add("created targets.default"); }
         if (!plan.has("setup")) plan.add("setup",new JsonArray()); if (!plan.has("cleanup")) plan.add("cleanup",new JsonArray());
         plan.addProperty("schema_version",TestPlanValidator.CURRENT_VERSION); changes.add("updated schema_version to 1.0");
      } else if (!from.equals(TestPlanValidator.CURRENT_VERSION)) throw new IllegalArgumentException("Unsupported plan schema version: "+from);
      JsonObject result=new JsonObject(); result.addProperty("from_version",from); result.addProperty("to_version",TestPlanValidator.CURRENT_VERSION); result.addProperty("changed",!changes.isEmpty()); result.add("changes",changes); result.add("plan",plan); return result;
   }
}
