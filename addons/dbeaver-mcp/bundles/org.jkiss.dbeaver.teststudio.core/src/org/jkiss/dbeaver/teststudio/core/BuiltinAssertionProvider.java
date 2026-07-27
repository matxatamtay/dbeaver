package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.jkiss.dbeaver.teststudio.model.AssertionStatus;
import org.jkiss.dbeaver.teststudio.spi.*;

final class BuiltinAssertionProvider implements TestAssertionProvider {
   private final String type;
   BuiltinAssertionProvider(String type){this.type=type;}
   @Override public String type(){return type;}
   @Override public JsonObject evaluate(AssertionContext context,JsonObject config){
      JsonElement actual=select(context.actual(),StudioJson.string(config,"path","")); JsonElement expected=config.has("expected")?config.get("expected"):JsonNull.INSTANCE;
      boolean passed; String message="";
      try { passed=switch(type){
         case "json"->jsonOperator(actual,expected,StudioJson.string(config,"operator","equals"));
         case "query_equals","set_equals"->canonicalRows(actual).equals(canonicalRows(expected));
         case "query_contains"->contains(actual,expected);
         case "row_count"->rowCount(actual)==expected.getAsInt();
         case "scalar_equals"->scalar(actual).equals(expected);
         case "no_duplicates"->noDuplicates(actual,StudioJson.array(config,"columns"));
         case "column_not_null"->columnNotNull(actual,StudioJson.required(config,"column"));
         case "value_matches"->Pattern.compile(StudioJson.required(config,"pattern")).matcher(scalar(actual).getAsString()).find();
         case "numeric_range"->numericRange(scalar(actual),config);
         case "referential_integrity","no_blocking_locks"->rowCount(actual)==0;
         case "object_exists","column_exists","constraint_exists","foreign_key_exists","index_exists"->actual!=null&&!actual.isJsonNull();
         case "column_type"->actual!=null&&actual.isJsonPrimitive()&&actual.getAsString().equalsIgnoreCase(expected.getAsString());
         case "permission_allowed"->actual!=null&&actual.isJsonPrimitive()&&actual.getAsBoolean();
         case "permission_denied"->actual!=null&&actual.isJsonPrimitive()&&!actual.getAsBoolean();
         case "duration_below"->number(actual).compareTo(number(expected))<0;
         case "plan_contains"->text(actual).toLowerCase(Locale.ENGLISH).contains(expected.getAsString().toLowerCase(Locale.ENGLISH));
         case "plan_not_contains"->!text(actual).toLowerCase(Locale.ENGLISH).contains(expected.getAsString().toLowerCase(Locale.ENGLISH));
         case "no_sequential_scan"->!text(actual).toLowerCase(Locale.ENGLISH).contains("seq scan");
         case "session_count_below","replication_lag_below","database_size_below"->number(actual).compareTo(number(expected))<0;
         default->throw new IllegalArgumentException("Unsupported built-in assertion type: "+type);
      }; } catch(Exception e){passed=false;message=StudioJson.safe(e);}
      JsonObject result=new JsonObject();result.addProperty("type",type);result.addProperty("status",(passed?AssertionStatus.PASSED:AssertionStatus.FAILED).name().toLowerCase());result.addProperty("passed",passed);result.add("expected",StudioJson.bounded(expected,4096));result.add("actual",StudioJson.bool(config,"sensitive",false)?new JsonPrimitive("***"):StudioJson.bounded(actual==null?JsonNull.INSTANCE:actual,4096));if(!message.isBlank())result.addProperty("message",message);return result;
   }
   private static JsonElement select(JsonElement value,String path){return path.isBlank()?value:StudioJson.pointer(value,path);}
   private static boolean jsonOperator(JsonElement a,JsonElement e,String op){return switch(op){case "exists"->a!=null;case "absent"->a==null;case "equals"->Objects.equals(a,e);case "not_equals"->!Objects.equals(a,e);case "contains"->contains(a,e);case "gt"->number(a).compareTo(number(e))>0;case "gte"->number(a).compareTo(number(e))>=0;case "lt"->number(a).compareTo(number(e))<0;case "lte"->number(a).compareTo(number(e))<=0;case "empty"->size(a)==0;case "not_empty"->size(a)>0;default->throw new IllegalArgumentException("Unsupported JSON operator: "+op);};}
   private static boolean contains(JsonElement a,JsonElement e){if(a==null)return false;if(a.isJsonArray()){for(JsonElement item:a.getAsJsonArray())if(Objects.equals(item,e))return true;return false;}return text(a).contains(e==null?"null":text(e));}
   private static int size(JsonElement a){if(a==null||a.isJsonNull())return 0;if(a.isJsonArray())return a.getAsJsonArray().size();if(a.isJsonObject())return a.getAsJsonObject().size();return text(a).length();}
   private static BigDecimal number(JsonElement a){if(a==null||!a.isJsonPrimitive())throw new IllegalArgumentException("Value is not numeric");return new BigDecimal(a.getAsString());}
   private static String text(JsonElement a){return a==null||a.isJsonNull()?"":a.isJsonPrimitive()?a.getAsString():StudioJson.GSON.toJson(a);}
   private static JsonElement scalar(JsonElement a){if(a==null)return JsonNull.INSTANCE;if(a.isJsonArray()&&!a.getAsJsonArray().isEmpty()){JsonElement first=a.getAsJsonArray().get(0);if(first.isJsonObject()&&!first.getAsJsonObject().isEmpty())return first.getAsJsonObject().entrySet().iterator().next().getValue();return first;}if(a.isJsonObject()){if(a.getAsJsonObject().has("rows"))return scalar(a.getAsJsonObject().get("rows"));if(!a.getAsJsonObject().isEmpty())return a.getAsJsonObject().entrySet().iterator().next().getValue();}return a;}
   private static int rowCount(JsonElement a){if(a==null)return 0;if(a.isJsonObject()&&a.getAsJsonObject().has("row_count"))return a.getAsJsonObject().get("row_count").getAsInt();if(a.isJsonObject()&&a.getAsJsonObject().has("rows"))return rowCount(a.getAsJsonObject().get("rows"));if(a.isJsonArray())return a.getAsJsonArray().size();return 1;}
   private static JsonArray rows(JsonElement a){if(a!=null&&a.isJsonObject()&&a.getAsJsonObject().has("rows"))a=a.getAsJsonObject().get("rows");return a!=null&&a.isJsonArray()?a.getAsJsonArray():new JsonArray();}
   private static JsonElement canonicalRows(JsonElement a){JsonArray copy=new JsonArray();for(JsonElement row:rows(a))copy.add(StudioJson.canonical(row));List<JsonElement> list=new ArrayList<>();copy.forEach(list::add);list.sort(Comparator.comparing(StudioJson.GSON::toJson));JsonArray out=new JsonArray();list.forEach(out::add);return out;}
   private static boolean noDuplicates(JsonElement a,JsonArray columns){Set<String> seen=new HashSet<>();for(JsonElement row:rows(a)){JsonElement key=row;if(row.isJsonObject()&&!columns.isEmpty()){JsonObject k=new JsonObject();for(JsonElement c:columns){String name=c.getAsString();k.add(name,row.getAsJsonObject().has(name)?row.getAsJsonObject().get(name):JsonNull.INSTANCE);}key=k;}if(!seen.add(StudioJson.GSON.toJson(StudioJson.canonical(key))))return false;}return true;}
   private static boolean columnNotNull(JsonElement a,String column){for(JsonElement row:rows(a))if(!row.isJsonObject()||!row.getAsJsonObject().has(column)||row.getAsJsonObject().get(column).isJsonNull())return false;return true;}
   private static boolean numericRange(JsonElement actual,JsonObject config){BigDecimal value=number(actual);if(config.has("minimum")&&value.compareTo(number(config.get("minimum")))<0)return false;if(config.has("maximum")&&value.compareTo(number(config.get("maximum")))>0)return false;return true;}
}
