/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.mcp;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import org.osgi.framework.BundleContext;

final class McpLog {
   private static final int MAX_MEMORY_ENTRIES = 1000;
   private static final long MAX_FILE_BYTES = 2097152L;
   private static final Object lock = new Object();
   private static final Deque<String> entries = new ArrayDeque<>();
   private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX");
   private static Path logFile;

   private McpLog() {
   }

   static void initialize(BundleContext context) {
      synchronized (lock) {
         File file = context.getDataFile("mcp.log");
         logFile = file == null ? null : file.toPath();
         if (logFile != null) {
            try {
               Files.createDirectories(logFile.getParent());
            } catch (Exception var5) {
               logFile = null;
            }
         }
      }

      info("MCP log initialized" + (getLogPath() == null ? " in memory" : " at " + getLogPath()));
   }

   static void info(String message) {
      append("INFO", message, null);
   }

   static void warn(String message) {
      append("WARN", message, null);
   }

   static void error(String message, Throwable error) {
      append("ERROR", message, error);
   }

   static String getText() {
      synchronized (lock) {
         return String.join(System.lineSeparator(), entries);
      }
   }

   static Path getLogPath() {
      synchronized (lock) {
         return logFile;
      }
   }

   static void clear() {
      synchronized (lock) {
         entries.clear();
         if (logFile != null) {
            try {
               Files.deleteIfExists(logFile);
               Files.deleteIfExists(rotatedPath(logFile));
            } catch (Exception var3) {
            }
         }
      }
   }

   private static void append(String level, String message, Throwable error) {
      StringBuilder line = new StringBuilder().append(TIMESTAMP.format(OffsetDateTime.now())).append(" [").append(level).append("] ").append(message);
      if (error != null) {
         StringWriter stack = new StringWriter();
         error.printStackTrace(new PrintWriter(stack));
         line.append(System.lineSeparator()).append(stack);
      }

      String entry = line.toString();
      synchronized (lock) {
         entries.addLast(entry);

         while (entries.size() > 1000) {
            entries.removeFirst();
         }

         writeFile(entry);
      }
   }

   private static void writeFile(String entry) {
      if (logFile != null) {
         try {
            rotateIfNeeded();
            Files.writeString(logFile, entry + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         } catch (Exception var2) {
         }
      }
   }

   private static void rotateIfNeeded() throws Exception {
      if (logFile != null && Files.exists(logFile) && Files.size(logFile) >= 2097152L) {
         Files.move(logFile, rotatedPath(logFile), StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static Path rotatedPath(Path file) {
      return file.resolveSibling(file.getFileName() + ".1");
   }
}
