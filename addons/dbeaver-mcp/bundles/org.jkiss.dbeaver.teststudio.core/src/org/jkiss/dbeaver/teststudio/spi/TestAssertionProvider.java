package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonObject;

public interface TestAssertionProvider {
   String type();
   default int priority() { return 100; }
   default JsonObject schema() { return new JsonObject(); }
   default void validate(JsonObject config) throws Exception { }
   JsonObject evaluate(AssertionContext context, JsonObject config) throws Exception;
}
