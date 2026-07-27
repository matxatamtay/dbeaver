package org.jkiss.dbeaver.mcp;

public final class SqlSafetyTest {
    public static void main(String[] args) {
        check(SqlSafety.isReadOnly("select 1"), "SELECT should be read-only");
        check(SqlSafety.isReadOnly("-- update ignored\nselect 'delete ignored'"), "comments and strings should be ignored");
        check(SqlSafety.isReadOnly("with x as (select 1) select * from x"), "read-only CTE should pass");
        check(!SqlSafety.isReadOnly("with x as (delete from t returning *) select * from x"), "write CTE should fail");
        check(!SqlSafety.isReadOnly("update t set value = 1"), "UPDATE should fail");
        check(!SqlSafety.isReadOnly("select value into backup from t"), "SELECT INTO should fail");
        System.out.println("SqlSafetyTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
