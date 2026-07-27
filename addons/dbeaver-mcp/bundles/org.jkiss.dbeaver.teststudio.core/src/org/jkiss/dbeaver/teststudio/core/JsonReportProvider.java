package org.jkiss.dbeaver.teststudio.core;
import com.google.gson.JsonObject;import java.nio.charset.StandardCharsets;import org.jkiss.dbeaver.teststudio.spi.TestReportProvider;
final class JsonReportProvider implements TestReportProvider{public String format(){return "json";}public String extension(){return "json";}public byte[] generate(JsonObject run,JsonObject options){return StudioJson.GSON.toJson(run).getBytes(StandardCharsets.UTF_8);}}
