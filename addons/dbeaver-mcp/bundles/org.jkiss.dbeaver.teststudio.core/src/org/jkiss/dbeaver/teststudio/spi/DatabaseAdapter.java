package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

public interface DatabaseAdapter {
   String id();
   default int priority() { return 100; }
   boolean supports(String productName, String driverId);
   JsonObject capabilities();
   String quoteIdentifier(String identifier);
   String literal(JsonElement value);

   default String insertSql(String qualifiedTable, List<String> columns, List<JsonElement> values) {
      if (columns.isEmpty() || columns.size() != values.size()) throw new IllegalArgumentException("Fixture columns and values must have the same non-zero size");
      String table = java.util.Arrays.stream(qualifiedTable.split("\\.")).map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining("."));
      String names = columns.stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "));
      String literals = values.stream().map(this::literal).collect(java.util.stream.Collectors.joining(", "));
      return "INSERT INTO " + table + " (" + names + ") VALUES (" + literals + ")";
   }
}
