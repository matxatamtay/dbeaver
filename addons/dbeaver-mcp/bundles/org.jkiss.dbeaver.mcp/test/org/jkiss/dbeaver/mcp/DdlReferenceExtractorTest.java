package org.jkiss.dbeaver.mcp;

import java.util.List;

public final class DdlReferenceExtractorTest {
    public static void main(String[] args) {
        String ddl = """
            CREATE TRIGGER trg AFTER UPDATE ON sales.orders
            FOR EACH ROW EXECUTE FUNCTION audit.log_order_change();
            INSERT INTO audit.order_events(event_type) VALUES ('update sales.hidden');
            SELECT * FROM sales.customers c JOIN auth.users u ON u.id = c.user_id;
            """;
        List<DdlReferenceExtractor.Reference> refs = DdlReferenceExtractor.extract(ddl);
        check(has(refs, "execute", "audit.log_order_change"), "trigger routine should be extracted");
        check(has(refs, "insert_into", "audit.order_events"), "write target should be extracted");
        check(has(refs, "from", "sales.customers"), "FROM target should be extracted");
        check(has(refs, "join", "auth.users"), "JOIN target should be extracted");
        check(refs.stream().noneMatch(ref -> ref.objectName().equalsIgnoreCase("on")), "trigger header ON must not be a write target");
        check(DdlReferenceExtractor.hasDynamicSql("RETURN QUERY EXECUTE format('select * from %I', table_name)"), "PL/pgSQL dynamic SQL should be detected");
        check(!DdlReferenceExtractor.hasDynamicSql("select * from sales.orders"), "ordinary SQL should not be dynamic");
        System.out.println("DdlReferenceExtractorTest passed");
    }

    private static boolean has(List<DdlReferenceExtractor.Reference> refs, String operation, String objectName) {
        return refs.stream().anyMatch(ref -> operation.equals(ref.operation()) && objectName.equals(ref.objectName()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + ": " + condition);
    }
}
