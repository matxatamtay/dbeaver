package org.jkiss.dbeaver.teststudio.model;

import com.google.gson.JsonObject;

public record StudioTarget(String alias, String connection, String project, boolean autoConnect) {
   public StudioTarget {
      if (alias == null || alias.isBlank()) throw new IllegalArgumentException("Target alias is required");
      if (connection == null || connection.isBlank()) throw new IllegalArgumentException("Target connection is required");
      project = project == null ? "" : project;
   }

   public JsonObject toJson() {
      JsonObject result = new JsonObject();
      result.addProperty("alias", alias);
      result.addProperty("connection", connection);
      result.addProperty("project", project);
      result.addProperty("auto_connect", autoConnect);
      return result;
   }
}
