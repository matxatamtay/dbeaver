package org.jkiss.dbeaver.teststudio.model;

public enum SandboxStrategy {
   TRANSACTION,
   SAVEPOINT,
   EXPLICIT_CLEANUP,
   TEMP_SCHEMA,
   READ_ONLY,
   NONE;

   public static SandboxStrategy parse(String value) {
      if (value == null || value.isBlank()) return TRANSACTION;
      return valueOf(value.trim().toUpperCase(java.util.Locale.ENGLISH).replace('-', '_'));
   }
}
