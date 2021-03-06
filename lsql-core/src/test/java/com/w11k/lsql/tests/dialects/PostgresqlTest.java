package com.w11k.lsql.tests.dialects;

import com.w11k.lsql.dialects.BaseDialect;
import com.w11k.lsql.dialects.PostgresDialect;

public class PostgresqlTest extends AbstractDialectTests {

    @Override
    public BaseDialect createDialect() {
        return new PostgresDialect();
    }

    @Override
    protected void setupTestTable() {
        lSql.executeRawSql("CREATE TABLE table1 (id SERIAL PRIMARY KEY, age INTEGER)");
    }

    @Override
    protected String getBlobColumnType() {
        return "bytea";
    }

}
