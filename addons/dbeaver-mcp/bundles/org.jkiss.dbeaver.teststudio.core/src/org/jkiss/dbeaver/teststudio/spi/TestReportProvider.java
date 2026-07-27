package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonObject;

public interface TestReportProvider {
   String format();
   String extension();
   default int priority() { return 100; }
   byte[] generate(JsonObject canonicalRun, JsonObject options) throws Exception;
}
