package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import org.jkiss.dbeaver.teststudio.model.SandboxStrategy;
import org.jkiss.dbeaver.teststudio.model.StudioTarget;

public interface StudioBridge {
   String id();
   default int priority() { return 100; }
   JsonObject capabilities();
   boolean confirm(String title, String message) throws Exception;
   StudioSession openSession(StudioTarget target, SandboxStrategy strategy) throws Exception;
   default JsonObject captureScreenshot(Path output) throws Exception {
      JsonObject result = new JsonObject();
      result.addProperty("supported", false);
      result.addProperty("reason", "Bridge does not implement screenshots");
      return result;
   }
}
