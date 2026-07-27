/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DBeaverCsvCodec {
   private DBeaverCsvCodec() {
   }

   static void write(Writer writer, List<String> columns, List<Map<String, Object>> rows) throws IOException {
      writeRecord(writer, columns);
      for (Map<String, Object> row : rows) {
         List<String> values = new ArrayList<>(columns.size());
         for (String column : columns) {
            Object value = row.get(column);
            values.add(value == null ? "" : String.valueOf(value));
         }
         writeRecord(writer, values);
      }
   }

   static List<Map<String, Object>> read(Reader reader, int maxRows) throws IOException {
      List<List<String>> records = parseRecords(reader, maxRows + 1);
      if (records.isEmpty()) {
         return List.of();
      }
      List<String> headers = records.getFirst();
      if (headers.isEmpty()) {
         throw new IllegalArgumentException("CSV header is empty");
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (int index = 1; index < records.size() && rows.size() < maxRows; index++) {
         List<String> record = records.get(index);
         Map<String, Object> row = new LinkedHashMap<>();
         for (int column = 0; column < headers.size(); column++) {
            String name = headers.get(column).trim();
            if (name.isEmpty()) {
               throw new IllegalArgumentException("CSV header contains an empty column name");
            }
            row.put(name, column < record.size() ? record.get(column) : "");
         }
         rows.add(row);
      }
      return List.copyOf(rows);
   }

   private static void writeRecord(Writer writer, List<String> values) throws IOException {
      for (int index = 0; index < values.size(); index++) {
         if (index > 0) {
            writer.write(',');
         }
         String value = values.get(index);
         boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
         if (quote) {
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
         } else {
            writer.write(value);
         }
      }
      writer.write(System.lineSeparator());
   }

   private static List<List<String>> parseRecords(Reader reader, int maxRecords) throws IOException {
      List<List<String>> records = new ArrayList<>();
      List<String> record = new ArrayList<>();
      StringBuilder field = new StringBuilder();
      boolean quoted = false;
      boolean sawAny = false;
      int value;
      while ((value = reader.read()) != -1) {
         sawAny = true;
         char ch = (char)value;
         if (quoted) {
            if (ch == '"') {
               reader.mark(1);
               int next = reader.read();
               if (next == '"') {
                  field.append('"');
               } else {
                  quoted = false;
                  if (next != -1) {
                     reader.reset();
                  }
               }
            } else {
               field.append(ch);
            }
         } else if (ch == '"' && field.isEmpty()) {
            quoted = true;
         } else if (ch == ',') {
            record.add(field.toString());
            field.setLength(0);
         } else if (ch == '\n' || ch == '\r') {
            if (ch == '\r') {
               reader.mark(1);
               int next = reader.read();
               if (next != '\n' && next != -1) {
                  reader.reset();
               }
            }
            record.add(field.toString());
            field.setLength(0);
            records.add(List.copyOf(record));
            record.clear();
            if (records.size() >= maxRecords) {
               return List.copyOf(records);
            }
         } else {
            field.append(ch);
         }
      }
      if (quoted) {
         throw new IllegalArgumentException("CSV contains an unterminated quoted field");
      }
      if (sawAny && (!field.isEmpty() || !record.isEmpty())) {
         record.add(field.toString());
         records.add(List.copyOf(record));
      }
      return List.copyOf(records);
   }
}
