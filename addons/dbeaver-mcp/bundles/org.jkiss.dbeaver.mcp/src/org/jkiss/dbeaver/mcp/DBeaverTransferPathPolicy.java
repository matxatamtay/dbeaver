/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DBeaverTransferPathPolicy {
   static final String PROPERTY_ROOT = "dbeaver.mcp.transferRoot";
   static final String ENV_ROOT = "DBEAVER_MCP_TRANSFER_ROOT";

   private final Path root;

   DBeaverTransferPathPolicy() throws IOException {
      this(resolveConfiguredRoot());
   }

   DBeaverTransferPathPolicy(Path root) throws IOException {
      Files.createDirectories(root);
      this.root = root.toRealPath();
   }

   Path root() {
      return this.root;
   }

   Path resolveInput(String value) throws IOException {
      Path candidate = resolve(value);
      if (!Files.isRegularFile(candidate)) {
         throw new IllegalArgumentException("Transfer input is not a regular file: " + candidate);
      }
      Path real = candidate.toRealPath();
      if (!real.startsWith(this.root)) {
         throw new IllegalArgumentException("Transfer input escapes the configured root: " + candidate);
      }
      return real;
   }

   Path resolveOutput(String value) throws IOException {
      Path candidate = resolve(value);
      Path parent = candidate.getParent();
      if (parent == null) {
         throw new IllegalArgumentException("Transfer output must have a parent directory");
      }
      Files.createDirectories(parent);
      Path realParent = parent.toRealPath();
      if (!realParent.startsWith(this.root)) {
         throw new IllegalArgumentException("Transfer path escapes the configured root: " + candidate);
      }
      Path output = realParent.resolve(candidate.getFileName()).normalize();
      if (Files.isSymbolicLink(output)) {
         throw new IllegalArgumentException("Transfer output may not be a symbolic link: " + output);
      }
      if (Files.exists(output) && !output.toRealPath().startsWith(this.root)) {
         throw new IllegalArgumentException("Transfer output escapes the configured root: " + output);
      }
      return output;
   }

   private Path resolve(String value) {
      if (value == null || value.isBlank()) {
         throw new IllegalArgumentException("Transfer path is required");
      }
      Path requested = Path.of(value);
      Path candidate = (requested.isAbsolute() ? requested : this.root.resolve(requested)).normalize();
      if (!candidate.startsWith(this.root)) {
         throw new IllegalArgumentException("Transfer path escapes the configured root: " + candidate);
      }
      return candidate;
   }

   private static Path resolveConfiguredRoot() {
      String configured = System.getProperty(PROPERTY_ROOT);
      if (configured == null || configured.isBlank()) {
         configured = System.getenv(ENV_ROOT);
      }
      if (configured == null || configured.isBlank()) {
         configured = Path.of(System.getProperty("user.home"), "DBeaverData").toString();
      }
      return Path.of(configured).toAbsolutePath().normalize();
   }
}
