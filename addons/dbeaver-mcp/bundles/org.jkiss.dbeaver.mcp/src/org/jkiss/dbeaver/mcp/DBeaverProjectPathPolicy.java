/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.jkiss.dbeaver.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class DBeaverProjectPathPolicy {
   private DBeaverProjectPathPolicy() {
   }

   static Path scriptsRoot(Path projectRoot, boolean create) throws IOException {
      Path root = projectRoot.resolve("Scripts").toAbsolutePath().normalize();
      if (create) Files.createDirectories(root);
      if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
         throw new IllegalArgumentException("Project Scripts root may not be a symbolic link");
      }
      return root;
   }

   static Path resolve(Path root, String relative, boolean requireExisting) throws IOException {
      Path candidate = root.resolve(relative).toAbsolutePath().normalize();
      if (!candidate.startsWith(root)) throw new IllegalArgumentException("Script path escapes the project Scripts folder");
      ensureParentsInside(root, requireExisting ? candidate : candidate.getParent());
      if (requireExisting && Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
         if (Files.isSymbolicLink(candidate) || !candidate.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("Script path escapes through a symbolic link");
         }
      }
      return candidate;
   }

   private static void ensureParentsInside(Path root, Path path) throws IOException {
      Path current = path;
      while (current != null && current.startsWith(root)) {
         if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("Symbolic links are not allowed in project script paths");
         }
         if (current.equals(root)) return;
         current = current.getParent();
      }
      throw new IllegalArgumentException("Script path escapes the project Scripts folder");
   }
}
