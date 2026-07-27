package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;

final class TestPlanStore {
   private static final String ROOT="Test Studio"; private static final String PLANS="Plans";
   private final TestPlanValidator validator;
   TestPlanStore(TestPlanValidator validator){this.validator=validator;}
   JsonObject list(String projectName) throws Exception {
      IFolder folder=plans(projectName,false); JsonArray items=new JsonArray(); if(folder==null||!folder.exists())return result(items);
      for(IResource resource:folder.members()) if(resource instanceof IFile file && file.getName().endsWith(".dbtest.json")) { try { JsonObject plan=read(file); JsonObject item=new JsonObject(); item.addProperty("id",StudioJson.string(plan,"id",strip(file.getName()))); item.addProperty("name",StudioJson.string(plan,"name",file.getName())); item.addProperty("schema_version",StudioJson.string(plan,"schema_version","")); item.addProperty("path",file.getProjectRelativePath().toString()); item.addProperty("modified",file.getLocalTimeStamp()); items.add(item);} catch(Exception e){JsonObject item=new JsonObject();item.addProperty("path",file.getProjectRelativePath().toString());item.addProperty("error",StudioJson.safe(e));items.add(item);} }
      return result(items);
   }
   JsonObject get(String project,String id) throws Exception { IFile file=file(project,id,false); if(file==null||!file.exists())throw new IllegalArgumentException("Test plan not found: "+id); JsonObject result=new JsonObject(); result.add("plan",read(file)); result.addProperty("path",file.getProjectRelativePath().toString()); return result; }
   JsonObject save(String project,JsonObject plan,boolean overwrite) throws Exception {
      JsonObject validation=validator.validate(plan); if(!validation.get("valid").getAsBoolean())throw new IllegalArgumentException("Invalid test plan: "+validation.getAsJsonArray("errors"));
      String id=StudioJson.required(plan,"id"); IFile file=file(project,id,true); byte[] bytes=StudioJson.GSON.toJson(plan).getBytes(StandardCharsets.UTF_8); boolean existed=file.exists(); if(existed&&!overwrite)throw new IllegalArgumentException("Plan exists; pass overwrite=true: "+id);
      try(ByteArrayInputStream in=new ByteArrayInputStream(bytes)){ if(existed)file.setContents(in,IResource.FORCE|IResource.KEEP_HISTORY,new NullProgressMonitor()); else file.create(in,IResource.FORCE,new NullProgressMonitor()); }
      JsonObject result=new JsonObject();result.addProperty("saved",true);result.addProperty("created",!existed);result.addProperty("path",file.getProjectRelativePath().toString());result.addProperty("fingerprint",validation.get("fingerprint").getAsString());return result;
   }
   JsonObject delete(String project,String id) throws Exception { IFile file=file(project,id,false); if(file==null||!file.exists())throw new IllegalArgumentException("Test plan not found: "+id); file.delete(IResource.KEEP_HISTORY,new NullProgressMonitor()); JsonObject result=new JsonObject();result.addProperty("deleted",true);result.addProperty("id",id);return result; }
   JsonObject clonePlan(String project,String id,String newId,String newName) throws Exception { JsonObject plan=get(project,id).getAsJsonObject("plan").deepCopy();plan.addProperty("id",newId);plan.addProperty("name",newName);plan.addProperty("updated_at",StudioJson.now());return save(project,plan,false); }
   private static JsonObject read(IFile file)throws Exception{try(var in=file.getContents()){return StudioJson.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
   private static JsonObject result(JsonArray items){JsonObject result=new JsonObject();result.addProperty("count",items.size());result.add("plans",items);return result;}
   private static String strip(String name){return name.substring(0,name.length()-".dbtest.json".length());}
   private static IProject project(String name){if(name==null||name.isBlank())throw new IllegalArgumentException("project is required");IProject p=ResourcesPlugin.getWorkspace().getRoot().getProject(name);if(!p.exists()||!p.isOpen())throw new IllegalArgumentException("Open DBeaver project not found: "+name);return p;}
   private static IFolder plans(String project,boolean create)throws Exception{IProject p=project(project);IFolder root=p.getFolder(ROOT);IFolder plans=root.getFolder(PLANS);if(create){if(!root.exists())root.create(true,true,new NullProgressMonitor());if(!plans.exists())plans.create(true,true,new NullProgressMonitor());}return plans;}
   private static IFile file(String project,String id,boolean create)throws Exception{if(!id.matches("[A-Za-z0-9._-]{1,128}"))throw new IllegalArgumentException("Invalid plan id");IFolder folder=plans(project,create);return folder==null?null:folder.getFile(id+".dbtest.json");}
}
