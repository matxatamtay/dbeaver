package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

final class VariableResolver {
   private static final SecureRandom RANDOM=new SecureRandom();
   JsonObject resolve(JsonObject definitions) {
      JsonObject result=new JsonObject();
      for(Map.Entry<String,JsonElement> entry:definitions.entrySet()) {
         JsonElement def=entry.getValue(); if(!def.isJsonObject()){result.add(entry.getKey(),def.deepCopy());continue;}
         JsonObject obj=def.getAsJsonObject();String generator=StudioJson.string(obj,"generator","");
         JsonElement value=switch(generator){case "uuid"->new JsonPrimitive(UUID.randomUUID().toString());case "timestamp"->new JsonPrimitive(Instant.now().toString());case "unique_email"->new JsonPrimitive("test+"+UUID.randomUUID().toString().substring(0,12)+"@example.invalid");case "random_string"->new JsonPrimitive(random(StudioJson.integer(obj,"length",16,1,128)));case "integer_range"->new JsonPrimitive(randomInt(StudioJson.integer(obj,"minimum",0,Integer.MIN_VALUE,Integer.MAX_VALUE),StudioJson.integer(obj,"maximum",100,Integer.MIN_VALUE,Integer.MAX_VALUE)));default->obj.has("value")?obj.get("value").deepCopy():JsonNull.INSTANCE;};
         result.add(entry.getKey(),value);
      }
      return result;
   }
   JsonObject masked(JsonObject values,JsonObject definitions){JsonObject result=values.deepCopy();for(String key:definitions.keySet())if(StudioJson.bool(StudioJson.object(definitions,key),"sensitive",false))result.addProperty(key,"***");return result;}
   private static String random(int length){String alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";StringBuilder out=new StringBuilder(length);for(int i=0;i<length;i++)out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));return out.toString();}
   private static int randomInt(int min,int max){if(max<min)throw new IllegalArgumentException("integer_range maximum must be >= minimum");long width=(long)max-min+1;return width<=Integer.MAX_VALUE?min+RANDOM.nextInt((int)width):(int)(min+Math.floorMod(RANDOM.nextLong(),width));}
}
