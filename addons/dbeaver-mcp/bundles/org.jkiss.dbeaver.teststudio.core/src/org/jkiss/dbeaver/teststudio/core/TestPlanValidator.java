package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

final class TestPlanValidator {
   static final String CURRENT_VERSION = "1.0";
   private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
   private static final Set<String> STEP_TYPES = Set.of("query","sql","call_tool","insert_fixture","import_fixture","wait_until","assert","snapshot","compare_snapshot","schema_contract","migration_rehearsal","group","parallel_read");
   private static final Set<String> SECRET_KEYS = Set.of("password","passwd","secret","token","credential","private_key","api_key","authorization","username","user_name");

   JsonObject validate(JsonObject input) {
      JsonObject plan = input.deepCopy();
      JsonArray errors = new JsonArray(); JsonArray warnings = new JsonArray();
      String version = StudioJson.string(plan, "schema_version", "");
      if (!CURRENT_VERSION.equals(version)) errors.add("schema_version must be " + CURRENT_VERSION + "; migrate the plan first");
      String id = StudioJson.string(plan, "id", "");
      if (!ID.matcher(id).matches()) errors.add("id must match [A-Za-z0-9._-]{1,128}");
      String name = StudioJson.string(plan, "name", "");
      if (name.isBlank() || name.length() > 200) errors.add("name must contain 1-200 characters");
      JsonObject targets = StudioJson.object(plan, "targets");
      if (targets.isEmpty()) errors.add("targets must contain at least one target");
      for (Map.Entry<String, JsonElement> entry : targets.entrySet()) {
         if (!entry.getValue().isJsonObject()) { errors.add("target " + entry.getKey() + " must be an object"); continue; }
         JsonObject target = entry.getValue().getAsJsonObject();
         if (StudioJson.string(target, "connection", "").isBlank()) errors.add("target " + entry.getKey() + " requires connection");
      }
      validateSteps(StudioJson.array(plan, "setup"), "setup", 100, errors, warnings);
      JsonArray steps = StudioJson.array(plan, "steps");
      if (steps.isEmpty()) errors.add("steps must contain at least one step");
      validateSteps(steps, "steps", 500, errors, warnings);
      validateSteps(StudioJson.array(plan, "cleanup"), "cleanup", 100, errors, warnings);
      scanSecrets(plan, "", errors);
      int bytes = StudioJson.GSON.toJson(plan).getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 1024 * 1024) errors.add("plan exceeds the 1 MiB safety limit");
      JsonObject result = new JsonObject(); result.addProperty("valid", errors.isEmpty()); result.addProperty("schema_version", version); result.addProperty("fingerprint", StudioJson.fingerprint(plan)); result.addProperty("bytes", bytes); result.add("errors", errors); result.add("warnings", warnings); result.add("plan", plan); return result;
   }

   private static void validateSteps(JsonArray steps, String section, int maximum, JsonArray errors, JsonArray warnings) {
      if (steps.size() > maximum) errors.add(section + " may contain at most " + maximum + " steps");
      Set<String> ids = new HashSet<>();
      for (int i=0;i<steps.size();i++) {
         JsonElement item=steps.get(i); if (!item.isJsonObject()) { errors.add(section+"["+i+"] must be an object"); continue; }
         JsonObject step=item.getAsJsonObject(); String type=StudioJson.string(step,"type","");
         if (!STEP_TYPES.contains(type)) errors.add(section+"["+i+"] has unsupported type: "+type);
         String id=StudioJson.string(step,"id",section+"-"+(i+1)); if (!ids.add(id)) errors.add(section+" contains duplicate step id: "+id);
         int timeout=StudioJson.integer(step,"timeout_seconds",30,1,3600); if (timeout>300) warnings.add(section+"["+i+"] timeout exceeds five minutes");
         if ((type.equals("sql")||type.equals("insert_fixture")||type.equals("import_fixture")) && !StudioJson.bool(step,"idempotent",false)) warnings.add(section+"["+i+"] is non-idempotent and will not be retried automatically");
      }
   }
   private static void scanSecrets(JsonElement value, String path, JsonArray errors) {
      if (value == null || !value.isJsonObject() && !value.isJsonArray()) return;
      if (value.isJsonArray()) { for (int i=0;i<value.getAsJsonArray().size();i++) scanSecrets(value.getAsJsonArray().get(i),path+"/"+i,errors); return; }
      for (Map.Entry<String,JsonElement> e:value.getAsJsonObject().entrySet()) {
         String normalized=e.getKey().toLowerCase(Locale.ENGLISH).replace('-','_');
         if (SECRET_KEYS.stream().anyMatch(normalized::contains) && !e.getValue().isJsonNull()) errors.add("secret-like field is forbidden in plans: "+path+"/"+e.getKey());
         scanSecrets(e.getValue(),path+"/"+e.getKey(),errors);
      }
   }
}
