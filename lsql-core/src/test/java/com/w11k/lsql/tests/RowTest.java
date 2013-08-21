package com.w11k.lsql.tests;

import com.google.common.base.Optional;
import com.w11k.lsql.relational.QueriedRow;
import com.w11k.lsql.relational.Row;
import org.testng.annotations.Test;

import java.util.Map;

import static junit.framework.Assert.assertFalse;
import static org.testng.Assert.assertEquals;

public class RowTest extends AbstractLSqlTest {

    @Test public void addKeyVals() {
        Row r = new Row().addKeyVals("a", 1, "b", "val");
        assertEquals(r.get("a"), 1);
        assertEquals(r.get("b"), "val");
    }

    @Test public void constructorCreatesDefensiveCopy() {
        Row row1 = new Row().addKeyVals("key1", "value1");
        Row row2 = new Row(row1);

        row1.put("key2", "value2");
        assertFalse(row2.containsKey("key2"));
    }

    @Test(expectedExceptions = ClassCastException.class)
    public void getAsThrowsClassCastExceptionOnWrongType() {
        Row r = new Row().addKeyVals("a", "1");
        assertEquals(r.getInt("a"), 1);
    }

    @Test public void groupByTable() {
        lSql.executeRawSql("CREATE TABLE city (id serial primary key, zipcode text, name text)");
        lSql.executeRawSql("CREATE TABLE person (id serial primary key, name text, zipcode integer references city (id))");

        Optional<Object> cityId = lSql.table("city").insert(Row.fromKeyVals("zipcode", "53721", "name", "Siegburg"));
        lSql.table("person").insert(Row.fromKeyVals("name", "John", "zipcode", cityId.get()));

        QueriedRow row = lSql.executeRawQuery("select * from person, city").getFirstRow();
        assertEquals(row.getString("city.zipcode"), "53721");
        assertEquals(row.getString("city.name"), "Siegburg");
        assertEquals(row.getString("person.name"), "John");
        assertEquals(row.getInt("person.zipcode"), cityId.get());

        Map<String, Row> byTables = row.groupByTables();
        assertEquals(byTables.get("city").getString("name"), "Siegburg");
        assertEquals(byTables.get("city").getString("zipcode"), "53721");
        assertEquals(byTables.get("person").getString("name"), "John");
        assertEquals(byTables.get("person").getInt("zipcode"), cityId.get());
    }

}