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

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jkiss.dbeaver.Log;

public final class DBeaverMcpServer {
   private static final Log log = Log.getLog(DBeaverMcpServer.class);
   private static final int MAX_REQUEST_BYTES = 1048576;
   private static DBeaverMcpServer instance;
   private static String lastError = "";
   private final HttpServer server;
   private final ExecutorService executor;
   private final int port;
   private final String authToken;
   private final McpToolRegistry tools;

   private DBeaverMcpServer(int port, String authToken) throws IOException {
      this.port = port;
      this.authToken = authToken;
      this.tools = DBeaverTools.createRegistry(port, !authToken.isBlank());
      this.server = HttpServer.create();
      this.executor = Executors.newCachedThreadPool(runnable -> {
         Thread thread = new Thread(runnable, "DBeaver MCP request");
         thread.setDaemon(true);
         return thread;
      });
      this.server.setExecutor(this.executor);
      this.server.createContext("/healthz", this::handleHealth);
      this.server.createContext("/mcp", this::handleMcp);
      this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
      this.server.start();
   }

   public static synchronized void startIfEnabled() {
      if (McpPreferences.effectiveConfig().autoStart()) {
         start();
      } else {
         McpLog.info("MCP server automatic startup is disabled");
      }
   }

   public static synchronized void start() {
      if (instance == null) {
         McpPreferences.Config config = McpPreferences.effectiveConfig();

         try {
            instance = new DBeaverMcpServer(config.port(), config.authToken());
            lastError = "";
            String message = "DBeaver MCP server listening on http://127.0.0.1:" + config.port() + "/mcp";
            log.info(message);
            McpLog.info(message + (config.authToken().isBlank() ? " without authentication" : " with bearer authentication"));
         } catch (IOException var2) {
            lastError = "Unable to start on port " + config.port() + ": " + McpJson.safeMessage(var2);
            log.error("Unable to start DBeaver MCP server on port " + config.port(), var2);
            McpLog.error(lastError, var2);
         }
      }
   }

   public static synchronized void restart() {
      stop();
      start();
   }

   public static synchronized void stop() {
      if (instance != null) {
         instance.server.stop(0);
         instance.executor.shutdownNow();
         instance = null;
         log.info("DBeaver MCP server stopped");
         McpLog.info("DBeaver MCP server stopped");
      }
   }

   public static synchronized DBeaverMcpServer.ServerStatus status() {
      return instance == null
         ? new DBeaverMcpServer.ServerStatus(false, 0, false, "", lastError)
         : new DBeaverMcpServer.ServerStatus(true, instance.port, !instance.authToken.isBlank(), "http://127.0.0.1:" + instance.port + "/mcp", lastError);
   }

   private void handleHealth(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
         exchange.getResponseHeaders().set("Allow", "GET, HEAD");
         sendText(exchange, 405, "Method Not Allowed");
      } else {
         JsonObject payload = DBeaverTools.statusPayload(this.port, !this.authToken.isBlank());
         if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, -1L);
            exchange.close();
         } else {
            sendJson(exchange, 200, payload);
         }
      }
   }

   private void handleMcp(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
         exchange.getResponseHeaders().set("Allow", "POST");
         McpLog.warn("Rejected MCP request with method " + exchange.getRequestMethod());
         sendText(exchange, 405, "Method Not Allowed");
      } else if (!isOriginAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
         McpLog.warn("Rejected MCP request from a non-loopback Origin");
         sendText(exchange, 403, "Origin is not allowed");
      } else if (!this.isAuthorized(exchange.getRequestHeaders())) {
         exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
         McpLog.warn("Rejected unauthorized MCP request");
         sendText(exchange, 401, "Unauthorized");
      } else {
         byte[] requestBytes = exchange.getRequestBody().readNBytes(1048577);
         if (requestBytes.length > 1048576) {
            sendText(exchange, 413, "Request body is too large");
         } else {
            JsonObject request;
            try {
               JsonElement parsed = JsonParser.parseString(new String(requestBytes, StandardCharsets.UTF_8));
               if (!parsed.isJsonObject()) {
                  sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request"));
                  return;
               }

               request = parsed.getAsJsonObject();
            } catch (RuntimeException var9) {
               sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32700, "Parse error"));
               return;
            }

            JsonElement id = request.has("id") ? request.get("id") : null;
            String method = McpJson.getString(request, "method", "");
            McpLog.info("MCP request: " + (method.isBlank() ? "<missing method>" : method));
            if (method.startsWith("notifications/")) {
               exchange.sendResponseHeaders(202, -1L);
               exchange.close();
            } else if (id == null) {
               exchange.sendResponseHeaders(202, -1L);
               exchange.close();
            } else {
               try {
                  JsonElement result = this.dispatch(method, McpJson.getObject(request, "params"));
                  sendJson(exchange, 200, jsonRpcResult(id, result));
               } catch (McpRequestException var7) {
                  sendJson(exchange, 200, jsonRpcError(id, var7.getCode(), var7.getMessage()));
               } catch (Exception var8) {
                  log.error("DBeaver MCP request failed: " + method, var8);
                  McpLog.error("DBeaver MCP request failed: " + method, var8);
                  sendJson(exchange, 200, jsonRpcError(id, -32603, McpJson.safeMessage(var8)));
               }
            }
         }
      }
   }

   private JsonElement dispatch(String method, JsonObject params) throws Exception {
      return switch (method) {
         case "initialize" -> this.initialize(params);
         case "ping" -> new JsonObject();
         case "tools/list" -> this.tools.listTools();
         case "tools/call" -> this.tools.call(params);
         default -> throw new McpRequestException(-32601, "Method not found: " + method);
      };
   }

   private JsonObject initialize(JsonObject params) {
      JsonObject result = new JsonObject();
      String requestedVersion = McpJson.getString(params, "protocolVersion", "2025-11-25");
      result.addProperty("protocolVersion", McpProtocol.negotiate(requestedVersion));
      JsonObject capabilities = new JsonObject();
      JsonObject tools = new JsonObject();
      tools.addProperty("listChanged", false);
      capabilities.add("tools", tools);
      result.add("capabilities", capabilities);
      JsonObject serverInfo = new JsonObject();
      serverInfo.addProperty("name", "dbeaver-desktop");
      serverInfo.addProperty("title", "DBeaver Desktop MCP");
      serverInfo.addProperty("version", "1.0.0");
      result.add("serverInfo", serverInfo);
      result.addProperty(
         "instructions",
         "Use DBeaver's live connections to inspect databases and execute SQL. SQL writes require allow_write=true. Discovery tools report coverage and blind spots when metadata is incomplete."
      );
      return result;
   }

   private boolean isAuthorized(Headers headers) {
      return this.authToken.isBlank() ? true : ("Bearer " + this.authToken).equals(headers.getFirst("Authorization"));
   }

   private static boolean isOriginAllowed(String origin) {
      if (origin != null && !origin.isBlank() && !"null".equals(origin)) {
         try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
         } catch (IllegalArgumentException var3) {
            return false;
         }
      } else {
         return true;
      }
   }

   private static JsonObject jsonRpcResult(JsonElement id, JsonElement result) {
      JsonObject response = new JsonObject();
      response.addProperty("jsonrpc", "2.0");
      response.add("id", id.deepCopy());
      response.add("result", result);
      return response;
   }

   private static JsonObject jsonRpcError(JsonElement id, int code, String message) {
      JsonObject response = new JsonObject();
      response.addProperty("jsonrpc", "2.0");
      response.add("id", id.deepCopy());
      JsonObject error = new JsonObject();
      error.addProperty("code", code);
      error.addProperty("message", message);
      response.add("error", error);
      return response;
   }

   private static void sendJson(HttpExchange exchange, int status, JsonElement payload) throws IOException {
      byte[] body = McpJson.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, body.length);
      HttpExchange var4 = exchange;

      try (OutputStream output = exchange.getResponseBody()) {
         output.write(body);
      } catch (Throwable var11) {
         if (exchange != null) {
            try {
               var4.close();
            } catch (Throwable var8) {
               var11.addSuppressed(var8);
            }
         }

         throw var11;
      }

      if (exchange != null) {
         exchange.close();
      }
   }

   private static void sendText(HttpExchange exchange, int status, String payload) throws IOException {
      byte[] body = payload.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(status, body.length);
      HttpExchange var4 = exchange;

      try (OutputStream output = exchange.getResponseBody()) {
         output.write(body);
      } catch (Throwable var11) {
         if (exchange != null) {
            try {
               var4.close();
            } catch (Throwable var8) {
               var11.addSuppressed(var8);
            }
         }

         throw var11;
      }

      if (exchange != null) {
         exchange.close();
      }
   }

   public record ServerStatus(boolean running, int port, boolean authRequired, String endpoint, String lastError) {
   }
}
