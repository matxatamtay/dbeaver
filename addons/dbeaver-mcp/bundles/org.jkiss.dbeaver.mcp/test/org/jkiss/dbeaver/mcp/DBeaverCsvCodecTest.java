package org.jkiss.dbeaver.mcp;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DBeaverCsvCodecTest {
    public static void main(String[] args) throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", 1);
        first.put("note", "hello, \"world\"\nnext");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", 2);
        second.put("note", "plain");

        StringWriter writer = new StringWriter();
        DBeaverCsvCodec.write(writer, List.of("id", "note"), List.of(first, second));
        List<Map<String, Object>> parsed = DBeaverCsvCodec.read(new StringReader(writer.toString()), 10);

        check(parsed.size() == 2, "two rows should round-trip");
        check("1".equals(parsed.get(0).get("id")), "numeric CSV values are text on import");
        check("hello, \"world\"\nnext".equals(parsed.get(0).get("note")), "quoted multiline value should round-trip");
        check("plain".equals(parsed.get(1).get("note")), "plain value should round-trip");

        boolean invalidRejected = false;
        try {
            DBeaverCsvCodec.read(new StringReader("id,note\n1,\"unterminated"), 10);
        } catch (IllegalArgumentException expected) {
            invalidRejected = true;
        }
        check(invalidRejected, "unterminated quoted field must be rejected");
        System.out.println("DBeaverCsvCodecTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
