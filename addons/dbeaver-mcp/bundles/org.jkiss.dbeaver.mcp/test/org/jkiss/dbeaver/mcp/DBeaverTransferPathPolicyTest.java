package org.jkiss.dbeaver.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DBeaverTransferPathPolicyTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("dbeaver-mcp-transfer-test-");
        try {
            DBeaverTransferPathPolicy policy = new DBeaverTransferPathPolicy(temp);
            Path output = policy.resolveOutput("exports/data.csv");
            check(output.startsWith(temp.toRealPath()), "relative output should stay under root");

            Path input = temp.resolve("input.json");
            Files.writeString(input, "[]");
            check(policy.resolveInput("input.json").equals(input.toRealPath()), "existing input should resolve");

            boolean traversalRejected = false;
            try {
                policy.resolveOutput("../escape.csv");
            } catch (IllegalArgumentException expected) {
                traversalRejected = true;
            }
            check(traversalRejected, "parent traversal must be rejected");

            Path outside = Files.createTempFile("dbeaver-mcp-outside-", ".csv");
            try {
                Path symlink = temp.resolve("linked.csv");
                try {
                    Files.createSymbolicLink(symlink, outside);
                    boolean outputSymlinkRejected = false;
                    try {
                        policy.resolveOutput("linked.csv");
                    } catch (IllegalArgumentException expected) {
                        outputSymlinkRejected = true;
                    }
                    check(outputSymlinkRejected, "symbolic-link output must be rejected");
                    boolean inputSymlinkRejected = false;
                    try {
                        policy.resolveInput("linked.csv");
                    } catch (IllegalArgumentException expected) {
                        inputSymlinkRejected = true;
                    }
                    check(inputSymlinkRejected, "symbolic-link input escaping root must be rejected");
                } catch (UnsupportedOperationException | SecurityException ignored) {
                    // Platform does not support symbolic links in this test environment.
                }
            } finally {
                Files.deleteIfExists(outside);
            }
            System.out.println("DBeaverTransferPathPolicyTest passed");
        } finally {
            deleteTree(temp);
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
