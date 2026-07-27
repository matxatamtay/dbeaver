package org.jkiss.dbeaver.teststudio.core;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class ApprovalStore {
   private static final Duration TTL = Duration.ofMinutes(5);
   private static final int MAX = 50;
   private final LinkedHashMap<String, Approval> approvals = new LinkedHashMap<>();

   synchronized JsonObject create(
      String project,
      String fingerprint,
      JsonObject resolvedPlan,
      JsonObject variables,
      boolean requiresDataWrite
   ) {
      cleanup();
      while (approvals.size() >= MAX) approvals.remove(approvals.keySet().iterator().next());
      String id = "studio-approval-" + UUID.randomUUID();
      Instant expires = Instant.now().plus(TTL);
      Approval approval = new Approval(
         id,
         project,
         fingerprint,
         resolvedPlan.deepCopy(),
         variables.deepCopy(),
         requiresDataWrite,
         expires
      );
      approvals.put(id, approval);
      JsonObject result = approval.summary();
      result.addProperty("one_time", true);
      return result;
   }

   synchronized Approval peek(String id) {
      cleanup();
      Approval approval = approvals.get(id);
      if (approval == null) throw new IllegalArgumentException("Run approval is missing or expired");
      return approval;
   }

   synchronized Approval consume(String id, String fingerprint) {
      cleanup();
      Approval approval = approvals.remove(id);
      if (approval == null) {
         throw new IllegalArgumentException("Run approval is missing, expired, cancelled, or already consumed");
      }
      if (!approval.fingerprint().equals(fingerprint)) {
         throw new IllegalArgumentException("Plan fingerprint changed after approval");
      }
      return approval;
   }

   synchronized JsonObject cancel(String id) {
      cleanup();
      JsonObject result = new JsonObject();
      result.addProperty("cancelled", approvals.remove(id) != null);
      result.addProperty("approval_id", id);
      return result;
   }

   private void cleanup() {
      Instant now = Instant.now();
      approvals.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
   }

   record Approval(
      String id,
      String project,
      String fingerprint,
      JsonObject plan,
      JsonObject variables,
      boolean requiresDataWrite,
      Instant expiresAt
   ) {
      JsonObject summary() {
         JsonObject result = new JsonObject();
         result.addProperty("approval_id", id);
         result.addProperty("project", project);
         result.addProperty("fingerprint", fingerprint);
         result.addProperty("requires_data_write", requiresDataWrite);
         result.addProperty("expires_at", expiresAt.toString());
         return result;
      }
   }
}
