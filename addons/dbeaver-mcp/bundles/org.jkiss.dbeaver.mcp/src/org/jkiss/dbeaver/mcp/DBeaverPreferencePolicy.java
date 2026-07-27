/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import java.util.regex.Pattern;

final class DBeaverPreferencePolicy {
   private static final Pattern SENSITIVE_KEY = Pattern.compile(
      "(?i).*(password|passwd|secret|token|credential|private.?key|api.?key|auth).*"
   );

   private DBeaverPreferencePolicy() {
   }

   static String requireSafeKey(String key) {
      if (key == null || key.isBlank() || key.length() > 256 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
         throw new IllegalArgumentException("Invalid preference key");
      }
      if (SENSITIVE_KEY.matcher(key).matches()) {
         throw new IllegalArgumentException("Sensitive preference keys cannot be read or modified through MCP");
      }
      return key;
   }
}
