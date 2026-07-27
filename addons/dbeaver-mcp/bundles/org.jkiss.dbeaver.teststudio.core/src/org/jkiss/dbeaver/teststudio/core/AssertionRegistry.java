package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.jkiss.dbeaver.teststudio.spi.*;

final class AssertionRegistry {
   private static final String EXT="org.jkiss.dbeaver.teststudio.core.assertionProvider";
   private final Map<String,TestAssertionProvider> providers=new LinkedHashMap<>();
   AssertionRegistry(){for(String type:List.of("json","query_equals","query_contains","row_count","scalar_equals","set_equals","no_duplicates","column_not_null","value_matches","numeric_range","referential_integrity","object_exists","column_exists","column_type","constraint_exists","foreign_key_exists","index_exists","permission_allowed","permission_denied","duration_below","plan_contains","plan_not_contains","no_sequential_scan","no_blocking_locks","session_count_below","replication_lag_below","database_size_below"))register(new BuiltinAssertionProvider(type));load();}
   JsonObject evaluate(JsonElement actual,JsonArray assertions,JsonObject stepResult,JsonObject variables,StudioSession session){JsonArray results=new JsonArray();int passed=0,skipped=0;for(JsonElement item:assertions){JsonObject config=item.getAsJsonObject();String type=StudioJson.string(config,"type","json");JsonObject result;TestAssertionProvider provider=providers.get(type);if(provider==null){result=new JsonObject();result.addProperty("type",type);result.addProperty("status","unsupported");result.addProperty("passed",false);result.addProperty("message","No assertion provider is installed");skipped++;}else try{provider.validate(config);result=provider.evaluate(new AssertionContext(actual,stepResult,variables,session),config);}catch(Exception e){result=new JsonObject();result.addProperty("type",type);result.addProperty("status","error");result.addProperty("passed",false);result.addProperty("message",StudioJson.safe(e));}if(result.has("passed")&&result.get("passed").getAsBoolean())passed++;results.add(result);}JsonObject summary=new JsonObject();summary.addProperty("passed",passed==assertions.size());summary.addProperty("count",assertions.size());summary.addProperty("passed_count",passed);summary.addProperty("failed_count",assertions.size()-passed-skipped);summary.addProperty("unsupported_count",skipped);summary.add("assertions",results);return summary;}
   JsonObject describe(){JsonArray items=new JsonArray();providers.values().forEach(p->{JsonObject i=new JsonObject();i.addProperty("type",p.type());i.add("schema",p.schema());items.add(i);});JsonObject r=new JsonObject();r.addProperty("count",items.size());r.add("providers",items);return r;}
   private void register(TestAssertionProvider p){providers.merge(p.type(),p,(a,b)->a.priority()<=b.priority()?a:b);}
   private void load(){IExtensionRegistry registry=Platform.getExtensionRegistry();if(registry==null)return;for(IConfigurationElement e:registry.getConfigurationElementsFor(EXT))try{Object instance=e.createExecutableExtension("class");if(instance instanceof TestAssertionProvider p)register(p);}catch(Exception ignored){}}
}
