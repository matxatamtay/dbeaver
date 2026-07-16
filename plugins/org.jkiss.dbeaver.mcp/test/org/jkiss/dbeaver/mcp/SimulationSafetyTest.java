package org.jkiss.dbeaver.mcp;

public final class SimulationSafetyTest {
    public static void main(String[] args) {
        check("update".equals(SimulationSafety.validate("update orders set status = 'paid' where id = 1")), "UPDATE SET should be allowed");
        check("insert".equals(SimulationSafety.validate("insert into orders(id) values (1);")), "single INSERT should be allowed");
        rejects("select * from orders");
        rejects("update orders set status='paid'; delete from orders");
        rejects("delete from orders; commit");
        rejects("call process_order()");
        System.out.println("SimulationSafetyTest passed");
    }

    private static void rejects(String sql) {
        try {
            SimulationSafety.validate(sql);
            throw new AssertionError("Expected rejection: " + sql);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
