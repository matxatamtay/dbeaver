/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.jkiss.dbeaver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DBeaverMcpJobManager implements AutoCloseable {
   private static final int MAX_JOBS = 100;

   private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "DBeaver MCP job");
      thread.setDaemon(true);
      return thread;
   });
   private final Map<String, Entry> jobs = new ConcurrentHashMap<>();
   private final Deque<String> order = new ArrayDeque<>();

   public String submit(String providerId, String type, boolean cancellable, JobWork work) {
      String validatedProviderId = requireText(providerId, "providerId");
      String validatedType = requireText(type, "type");
      JobWork validatedWork = Objects.requireNonNull(work, "work");
      String jobId = "job-" + UUID.randomUUID();
      Entry entry = new Entry(jobId, validatedProviderId, validatedType, cancellable);
      synchronized (this.order) {
         this.ensureCapacityLocked();
         this.jobs.put(jobId, entry);
         this.order.addFirst(jobId);
      }
      entry.future = this.executor.submit(() -> this.run(entry, validatedWork));
      return jobId;
   }

   public JsonObject list(int limit) {
      JsonArray items = new JsonArray();
      List<String> ids;
      synchronized (this.order) {
         ids = new ArrayList<>(this.order);
      }
      ids.stream().limit(Math.max(1, Math.min(limit, MAX_JOBS))).map(this.jobs::get).filter(entry -> entry != null).forEach(entry -> items.add(entry.summary(false)));
      JsonObject result = new JsonObject();
      result.addProperty("count", items.size());
      result.add("jobs", items);
      return result;
   }

   public JsonObject get(String jobId, boolean includeResult) {
      return this.require(jobId).summary(includeResult);
   }

   public JsonObject cancel(String jobId) {
      Entry entry = this.require(jobId);
      if (!entry.cancellable) {
         throw new IllegalArgumentException("Job is not cancellable: " + jobId);
      }
      entry.cancellationRequested.set(true);
      Future<?> future = entry.future;
      boolean cancelled = future != null && future.cancel(true);
      synchronized (entry) {
         // A queued task that never entered run() has no cleanup to finish and can become terminal now.
         // A running task remains non-terminal until its work/finally blocks complete.
         if (cancelled && entry.startedAt == null) {
            entry.state = State.CANCELLED;
            entry.finishedAt = Instant.now();
         }
      }
      JsonObject result = entry.summary(false);
      result.addProperty("cancel_requested", true);
      result.addProperty("future_cancelled", cancelled);
      return result;
   }

   public int size() {
      return this.jobs.size();
   }

   @Override
   public void close() {
      this.executor.shutdownNow();
      this.jobs.values().forEach(entry -> entry.cancellationRequested.set(true));
   }

   private void run(Entry entry, JobWork work) {
      synchronized (entry) {
         if (entry.state == State.CANCELLED) {
            return;
         }
         entry.state = State.RUNNING;
         entry.startedAt = Instant.now();
      }
      try {
         JobContext context = new JobContext(entry.cancellationRequested);
         JsonObject result = work.run(context);
         if (entry.cancellationRequested.get() || Thread.currentThread().isInterrupted()) {
            entry.state = State.CANCELLED;
         } else {
            entry.result = result == null ? new JsonObject() : result.deepCopy();
            entry.state = State.SUCCEEDED;
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         entry.state = State.CANCELLED;
      } catch (Exception e) {
         entry.error = McpJson.safeMessage(e);
         entry.state = State.FAILED;
      } finally {
         entry.finishedAt = Instant.now();
      }
   }

   private Entry require(String jobId) {
      Entry entry = this.jobs.get(jobId);
      if (entry == null) {
         throw new IllegalArgumentException("Unknown MCP job: " + jobId);
      }
      return entry;
   }

   private static String requireText(String value, String name) {
      if (value == null || value.isBlank()) {
         throw new IllegalArgumentException(name + " is required");
      }
      return value;
   }

   private void ensureCapacityLocked() {
      while (this.order.size() >= MAX_JOBS) {
         String removable = null;
         for (var iterator = this.order.descendingIterator(); iterator.hasNext();) {
            String candidate = iterator.next();
            Entry entry = this.jobs.get(candidate);
            if (entry == null || entry.state.isTerminal()) {
               removable = candidate;
               break;
            }
         }
         if (removable == null) {
            throw new IllegalStateException("DBeaver MCP job capacity reached; wait for a running job to finish");
         }
         this.order.remove(removable);
         this.jobs.remove(removable);
      }
   }

   public enum State {
      QUEUED,
      RUNNING,
      SUCCEEDED,
      FAILED,
      CANCELLED;

      boolean isTerminal() {
         return this == SUCCEEDED || this == FAILED || this == CANCELLED;
      }
   }

   public static final class JobContext {
      private final AtomicBoolean cancellationRequested;

      private JobContext(AtomicBoolean cancellationRequested) {
         this.cancellationRequested = cancellationRequested;
      }

      public boolean isCancellationRequested() {
         return this.cancellationRequested.get() || Thread.currentThread().isInterrupted();
      }

      public void checkCancelled() throws InterruptedException {
         if (this.isCancellationRequested()) {
            throw new InterruptedException("MCP job cancelled");
         }
      }
   }

   @FunctionalInterface
   public interface JobWork {
      JsonObject run(JobContext context) throws Exception;
   }

   private static final class Entry {
      private final String id;
      private final String providerId;
      private final String type;
      private final boolean cancellable;
      private final Instant createdAt = Instant.now();
      private final AtomicBoolean cancellationRequested = new AtomicBoolean();
      private volatile State state = State.QUEUED;
      private volatile Instant startedAt;
      private volatile Instant finishedAt;
      private volatile JsonObject result;
      private volatile String error = "";
      private volatile Future<?> future;

      private Entry(String id, String providerId, String type, boolean cancellable) {
         this.id = id;
         this.providerId = providerId;
         this.type = type;
         this.cancellable = cancellable;
      }

      private JsonObject summary(boolean includeResult) {
         JsonObject item = new JsonObject();
         item.addProperty("job_id", this.id);
         item.addProperty("provider", this.providerId);
         item.addProperty("type", this.type);
         item.addProperty("state", this.state.name().toLowerCase());
         item.addProperty("cancellable", this.cancellable);
         item.addProperty("cancellation_requested", this.cancellationRequested.get());
         item.addProperty("created_at", this.createdAt.toString());
         if (this.startedAt != null) {
            item.addProperty("started_at", this.startedAt.toString());
         }
         if (this.finishedAt != null) {
            item.addProperty("finished_at", this.finishedAt.toString());
         }
         if (!this.error.isBlank()) {
            item.addProperty("error", this.error);
         }
         if (includeResult && this.result != null) {
            item.add("result", this.result.deepCopy());
         }
         return item;
      }
   }
}
