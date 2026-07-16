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

import java.util.Set;

final class McpProtocol {
   static final String LATEST_VERSION = "2025-11-25";
   private static final Set<String> SUPPORTED_VERSIONS = Set.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07");

   private McpProtocol() {
   }

   static String negotiate(String requestedVersion) {
      return SUPPORTED_VERSIONS.contains(requestedVersion) ? requestedVersion : "2025-11-25";
   }
}
