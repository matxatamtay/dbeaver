package org.jkiss.dbeaver.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Phase3PolicyTest {
   public static void main(String[] args) throws Exception {
      testPreferencePolicy();
      testProjectPathPolicy();
      System.out.println("Phase3PolicyTest passed");
   }

   private static void testPreferencePolicy() {
      check("resultset.maxrows".equals(DBeaverPreferencePolicy.requireSafeKey("resultset.maxrows")), "ordinary key should pass");
      reject(() -> DBeaverPreferencePolicy.requireSafeKey("database.password"), "password key must be denied");
      reject(() -> DBeaverPreferencePolicy.requireSafeKey("apiToken"), "token key must be denied");
      reject(() -> DBeaverPreferencePolicy.requireSafeKey("bad\nkey"), "newline key must be denied");
   }

   private static void testProjectPathPolicy() throws Exception {
      Path temp = Files.createTempDirectory("mcp-phase3-path");
      Path root = DBeaverProjectPathPolicy.scriptsRoot(temp, true);
      Path script = DBeaverProjectPathPolicy.resolve(root, "folder/test.sql", false);
      check(script.startsWith(root), "normal script must remain under root");
      reject(() -> DBeaverProjectPathPolicy.resolve(root, "../escape.sql", false), "parent traversal must be denied");
      Path outside = Files.createTempDirectory("mcp-phase3-outside");
      Path link = root.resolve("link");
      try {
         Files.createSymbolicLink(link, outside);
         reject(() -> DBeaverProjectPathPolicy.resolve(root, "link/escape.sql", false), "symlink escape must be denied");
      } catch (UnsupportedOperationException ignored) {
         // Platform does not support symbolic links.
      }
   }

   private static void reject(ThrowingRunnable action, String message) {
      try {
         action.run();
      } catch (Exception expected) {
         return;
      }
      throw new AssertionError(message);
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Exception;
   }
}
