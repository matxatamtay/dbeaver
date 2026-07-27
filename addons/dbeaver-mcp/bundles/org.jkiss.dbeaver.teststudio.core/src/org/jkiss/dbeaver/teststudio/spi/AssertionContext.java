package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record AssertionContext(JsonElement actual, JsonObject stepResult, JsonObject variables, StudioSession session) {
}
