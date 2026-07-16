package org.jkiss.dbeaver.mcp;

public final class McpProtocolTest {
    public static void main(String[] args) {
        check("2025-11-25".equals(McpProtocol.negotiate("2025-11-25")), "latest version should be preserved");
        check("2024-11-05".equals(McpProtocol.negotiate("2024-11-05")), "supported older version should be preserved");
        check(McpProtocol.LATEST_VERSION.equals(McpProtocol.negotiate("2099-01-01")), "unknown version should fall back");
        System.out.println("McpProtocolTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
