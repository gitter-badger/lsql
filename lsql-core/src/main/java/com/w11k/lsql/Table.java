package com.w11k.lsql;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.w11k.lsql.converter.Converter;
import com.w11k.lsql.exceptions.DatabaseAccessException;
import com.w11k.lsql.exceptions.DeleteException;
import com.w11k.lsql.exceptions.InsertException;
import com.w11k.lsql.exceptions.UpdateException;
import com.w11k.lsql.jdbc.ConnectionUtils;
import com.w11k.lsql.validation.AbstractValidationError;
import com.w11k.lsql.validation.KeyError;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Lists.newLinkedList;

public class Table {

    private final LSql lSql;

    private final String tableName;

    private final Map<String, Column> columns = Maps.newHashMap();

    private Optional<String> primaryKeyColumn = absent();

    private Optional<Column> revisionColumn = absent();

    public static Table create(LSql lSql, String tableName) {
        return new Table(lSql, tableName);
    }

    public Table(LSql lSql, String tableName) {
        this.lSql = lSql;
        this.tableName = tableName;
        fetchMeta();
    }

    public Map<String, Converter> getColumnConverters() {
        Map<String, Converter> converters = Maps.newLinkedHashMap();
        for (String name : this.columns.keySet()) {
            Column column = this.columns.get(name);
            converters.put(name, column.getConverter());
        }
        return converters;
    }

    public LSql getlSql() {
        return lSql;
    }

    public String getTableName() {
        return tableName;
    }

    public Optional<String> getPrimaryKeyColumn() {
        return primaryKeyColumn;
    }

    public Map<String, Column> getColumns() {
        return ImmutableMap.copyOf(columns);
    }

    public boolean exists() {
        return columns.size() != 0;
    }

    /**
     * @param columnName the name of the column
     *
     * @return the column instance
     */
    public synchronized Column column(String columnName) {
        if (!columns.containsKey(columnName)) {
            return null;
        }
        return columns.get(columnName);
    }

    /**
     * Convenience method. Same as {@code enableRevisionSupport(revision).}
     */
    public void enableRevisionSupport() {
        enableRevisionSupport("revision");
    }

    /**
     * Enables revision support and optimistic locking with the given column. LSql increases the revision column
     * on
     * every update operation. Hence the column must support the SQL operation "SET column=column+1".
     * Additionally,
     * every {@link com.w11k.lsql.Table#update(Row)} operation uses a WHERE constraint with the expected revision.
     *
     * @param revisionColumnName the revision column
     */
    public void enableRevisionSupport(String revisionColumnName) {
        Column col = column(revisionColumnName);
        revisionColumn = of(col);
    }

    public Optional<Column> getRevisionColumn() {
        return revisionColumn;
    }

    /**
     * Inserts the given {@link Row}. If a primary key was generated during the INSERT operation, the key will be
     * put into the passed row and additionally be returned.
     * <p/>
     * If revision support is enabled (see {@link com.w11k.lsql.Table#enableRevisionSupport()}), the revision
     * value
     * will be queried after the insert operation and be put into the passed row.
     *
     * @param row the values to be inserted
     *
     * @throws InsertException
     */
    public Optional<Object> insert(Row row) {
        try {
            List<String> columns = createColumnList(row);

            PreparedStatement ps = lSql.getDialect().getPreparedStatementCreator()
                    .createInsertStatement(this, columns);
            setValuesInPreparedStatement(ps, columns, row);

            int rowsAffected = ps.executeUpdate();
            if (rowsAffected != 1) {
                throw new InsertException(rowsAffected + " toList were affected by insert operation. Expected: 1");
            }
            if (primaryKeyColumn.isPresent()) {
                Object id = null;
                if (!row.containsKey(primaryKeyColumn.get())) {
                    // check for generated keys
                    ResultSet resultSet = ps.getGeneratedKeys();
                    if (resultSet.next()) {
                        Optional<Object> generated = lSql.getDialect().extractGeneratedPk(this, resultSet);
                        if (generated.isPresent()) {
                            id = generated.get();
                            row.put(primaryKeyColumn.get(), id);
                        }
                    }
                } else {
                    id = row.get(primaryKeyColumn.get());
                }

                // Set new revision
                applyNewRevision(row, id);

                return of(id);
            }
        } catch (Exception e) {
            throw new InsertException(e);
        }
        return absent();
    }

    /**
     * Updates a database row with the values in the passed {@link Row}. If you want to set {@code null} values,
     * you need to explicitly add null entries for the columns.
     * <p/>
     * If revision support is enabled (see {@link com.w11k.lsql.Table#enableRevisionSupport()}), the revision
     * value
     * will be queried after the update operation and be put into the passed row.
     *
     * @param row The values used to update the database. The row instance must contain a primary key value and,
     *            if
     *            revision support is enabled, a revision value.
     *
     * @throws UpdateException
     */
    public void update(Row row) {
        if (getPrimaryKeyColumn().isPresent() && !row.containsKey(getPrimaryKeyColumn().get())) {
            throw new UpdateException("Can not update row because the primary key column " +
                    "'" + getPrimaryKeyColumn().get() + "' is not present.");
        }
        try {
            List<String> columns = createColumnList(row);
            columns.remove(getPrimaryKeyColumn().get());
            if (revisionColumn.isPresent()) {
                columns.remove(getRevisionColumn().get().getColumnName());
            }

            PreparedStatement ps = lSql.getDialect().getPreparedStatementCreator()
                    .createUpdateStatement(this, columns);
            setValuesInPreparedStatement(ps, columns, row);

            // Set ID
            String pkColumn = getPrimaryKeyColumn().get();
            Object id = row.get(pkColumn);
            Column column = column(pkColumn);
            column.getConverter().setValueInStatement(lSql, ps, columns.size() + 1, id, column.getSqlType());

            // Set Revision
            if (revisionColumn.isPresent()) {
                Column col = revisionColumn.get();
                Object revision = row.get(col.getColumnName());
                col.getConverter().setValueInStatement(lSql, ps, columns.size() + 2, revision, col.getSqlType());
            }

            executeUpdate(ps);

            // Set new revision
            applyNewRevision(row, id);

            //return row.getOptional(getPrimaryKeyColumn().load());
        } catch (Exception e) {
            throw new UpdateException(e);
        }
    }

    /**
     * Saves the {@link Row} instance.
     * <p/>
     * If the passed row does not contain a primary key value, {@link #insert(Row)} will be called. If the passed
     * row contains a primary key value, it will be checked if this key is already existent in the database. If it
     * is, {@link #update(Row)} will be called, {@link #insert(Row)} otherwise.
     *
     * @throws InsertException
     * @throws UpdateException
     */
    public Optional<?> save(Row row) {
        if (!primaryKeyColumn.isPresent()) {
            throw new DatabaseAccessException("save() requires a primary key column.");
        }
        if (!row.containsKey(getPrimaryKeyColumn().get())) {
            // Insert
            return insert(row);
        } else {
            // Check if insert or update
            Object id = row.get(primaryKeyColumn.get());
            try {
                PreparedStatement ps = lSql.getDialect().getPreparedStatementCreator()
                        .createCountForIdStatement(this);
                Column column = column(getPrimaryKeyColumn().get());
                column.getConverter().setValueInStatement(lSql, ps, 1, id, column.getSqlType());
                ps.setObject(1, id);
                ResultSet rs = ps.executeQuery();
                rs.next();
                int count = rs.getInt(1);
                if (count == 0) {
                    insert(row);
                } else {
                    update(row);
                }
            } catch (DatabaseAccessException dae) {
                throw dae;
            } catch (Exception e) {
                throw new DatabaseAccessException(e);
            }
            return of(id);
        }
    }

    /**
     * Deletes the row with the given primary key value.
     * <p/>
     * If revision support is enabled, the operation will fail. Use {@link #delete(Row)} instead.
     *
     * @param id delete the row with this primary key value
     */
    public void delete(Object id) {
        Row row = new Row();
        row.put(primaryKeyColumn.get(), id);
        delete(row);
    }

//    public Optional<?> save(P pojo) {
//        return save(pojoToRow(pojo));
//    }

    /**
     * Deletes the row that matches the primary key value and, if enabled, the revision value in the passed {@link
     * Row} instance.
     *
     * @throws com.w11k.lsql.exceptions.DeleteException
     */
    public void delete(Row row) {
        PreparedStatement ps = lSql.getDialect().getPreparedStatementCreator().createDeleteByIdStatement(this);
        try {
            Column column = column(getPrimaryKeyColumn().get());
            Object id = row.get(getPrimaryKeyColumn().get());
            column.getConverter().setValueInStatement(lSql, ps, 1, id, column.getSqlType());
            if (revisionColumn.isPresent()) {
                Column revCol = revisionColumn.get();
                Object revVal = row.get(revCol.getColumnName());
                if (revVal == null) {
                    throw new IllegalStateException("Row must contain a revision.");
                }
                revCol.getConverter().setValueInStatement(lSql, ps, 2, revVal, revCol.getSqlType());
            }
            executeUpdate(ps);
        } catch (Exception e) {
            throw new DeleteException(e);
        }
    }

    /**
     * Loads the row with the given primary key value.
     *
     * @param id the primary key
     *
     * @return a {@link com.google.common.base.Present} with a {@link Row} instance if the passed primary key
     * values matches a row in the database. {@link com.google.common.base.Absent} otherwise.
     */
    public Optional<LinkedRow> load(Object id) {
        if (!this.primaryKeyColumn.isPresent()) {
            throw new IllegalArgumentException("Can not load by ID, table has no primary column");
        }
        PreparedStatement ps = createLoadPreparedStatement();

        String pkColumn = getPrimaryKeyColumn().get();
        Column column = column(pkColumn);
        try {
            column.getConverter().setValueInStatement(lSql, ps, 1, id, column.getSqlType());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Query query = new Query(lSql, ps);
        for (String columnInTable : this.columns.keySet()) {
            query.addConverter(columnInTable, this.columns.get(columnInTable).getConverter());
        }
        Optional<Row> first = query.firstRow();
        if (first.isPresent()) {
            return of(newLinkedRow(first.get()));
        } else {
            return absent();
        }
    }

    /**
     * @see com.w11k.lsql.Table#newLinkedRow(java.util.Map)
     */
    public LinkedRow newLinkedRow() {
        Map<String, Object> empty = new HashMap<String, Object>();
        return newLinkedRow(empty);
    }

//    public Optional<P> loadPojo(Object id) {
//        return load(id).transform(new Function<LinkedRow, P>() {
//            @Nullable
//            @Override
//            public P apply(LinkedRow input) {
//                return rowToPojo(input);
//            }
//        });
//    }

    /**
     * @see com.w11k.lsql.Table#newLinkedRow(java.util.Map)
     */
    public LinkedRow newLinkedRow(Object... keyVals) {
        return newLinkedRow(Row.fromKeyVals(keyVals));
    }

    /**
     * Creates and returns a new {@link LinkedRow} linked to this table and adds {@code data}.
     * <p/>
     * A {@link LinkedRow} will call {@link #validate(String, Object)} on every
     * {@link LinkedRow#put(String, Object)} operation.
     *
     * @param data content to be added
     */
    public LinkedRow newLinkedRow(Map<String, Object> data) {
        LinkedRow linkedRow = new LinkedRow();
        linkedRow.setTable(this);
        linkedRow.setData(data);
        return linkedRow;
    }

    /**
     * Validates the passed {@link Row} instance. The validation will check
     * <ul>
     * <li>if all entries in the row instance match a database column ({@link com.w11k.lsql.validation.KeyError}),</li>
     * <li>if all entries have the correct type ({@link com.w11k.lsql.validation.TypeError}) and </li>
     * <li>if the String values are too long ({@link com.w11k.lsql.validation.StringTooLongError}).</li>
     * </ul>
     *
     * @return A {@link java.util.Map} with potential validation errors. The keys match the column names
     * and the values are subclasses of {@link com.w11k.lsql.validation.AbstractValidationError}.
     */
    public Map<String, AbstractValidationError> validate(Row row) {
        Map<String, AbstractValidationError> validationErrors = Maps.newHashMap();
        for (String key : row.keySet()) {
            Object value = row.get(key);
            Optional<? extends AbstractValidationError> error = validate(key, value);
            if (error.isPresent()) {
                validationErrors.put(key, error.get());
            }
        }
        return validationErrors;
    }

    /**
     * Same as {@link #validate(Row)} but limited to the passed column and value.
     */
    public Optional<? extends AbstractValidationError> validate(String javaColumnName, Object value) {
        if (!getColumns().containsKey(javaColumnName)) {
            return of(new KeyError(getTableName(), javaColumnName));
        }
        return column(javaColumnName).validateValue(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Table otherTable = (Table) o;
        return lSql == otherTable.lSql && tableName.equals(otherTable.tableName);
    }

    @Override
    public int hashCode() {
        int result = lSql.hashCode();
        result = 31 * result + tableName.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Table{tableName='" + tableName + "'}";
    }

    private PreparedStatement createLoadPreparedStatement() {
        Optional<String> primaryKeyColumn = getPrimaryKeyColumn();
        if (!primaryKeyColumn.isPresent()) {
            throw new IllegalStateException("table has no primary key column");
        }
        String pkColumn = primaryKeyColumn.get();
        Column column = column(pkColumn);
        String psString = lSql.getDialect().getPreparedStatementCreator().createSelectByIdStatement(this, column);
        return ConnectionUtils.prepareStatement(lSql, psString, false);
    }

    private List<String> createColumnList(Row row) {
        List<String> columns = row.getKeyList();
        columns = newLinkedList(filter(columns, new Predicate<String>() {
            public boolean apply(String input) {
                if (column(input) == null) {
                    throw new RuntimeException("Column " + input + " does not exist in table " + tableName);
                }
                return true;
            }
        }));
        return columns;
    }

    private void executeUpdate(PreparedStatement ps) throws SQLException {
        int rowsAffected = ps.executeUpdate();
        if (rowsAffected != 1) {
            throw new UpdateException(rowsAffected +
                    " toList were affected by update operation (expected 1). Either the ID or the revision (if enabled) is wrong.");
        }
    }

    private void applyNewRevision(Row row, Object id) throws SQLException {
        if (revisionColumn.isPresent()) {
            Object revision = queryRevision(id);
            row.put(revisionColumn.get().getColumnName(), revision);
        }
    }

    private Object queryRevision(Object id) throws SQLException {
        Column revCol = revisionColumn.get();
        PreparedStatement revQuery =
                lSql.getDialect().getPreparedStatementCreator().createRevisionQueryStatement(this, id);
        revCol.getConverter().setValueInStatement(lSql, revQuery, 1, id, revCol.getSqlType());
        ResultSet resultSet = revQuery.executeQuery();
        resultSet.next();
        return resultSet.getObject(1);
    }

    private void fetchMeta() {
        Connection con = ConnectionUtils.getConnection(lSql);
        try {
            DatabaseMetaData md = con.getMetaData();

            // Fetch Primary Key
            ResultSet primaryKeys = md.getPrimaryKeys(null, null, lSql.getDialect()
                    .identifierJavaToSql(tableName));
            if (!primaryKeys.next()) {
                primaryKeyColumn = Optional.absent();
            } else {
                String idColumn = primaryKeys.getString(4);
                primaryKeyColumn = of(lSql.getDialect().identifierSqlToJava(idColumn));
            }

            // Fetch all columns
            ResultSet columnsMetaData = md.getColumns(null, null, lSql.getDialect()
                    .identifierJavaToSql(tableName), null);
            while (columnsMetaData.next()) {
                String sqlColumnName = columnsMetaData.getString(4);
                int columnSize = columnsMetaData.getInt(7);
                String javaColumnName = lSql.getDialect().identifierSqlToJava(sqlColumnName);
                int dataType = columnsMetaData.getInt(5);
                Converter converter = lSql.getDialect().getConverterRegistry().getConverterForSqlType(dataType);
                Column column = new Column(of(this), javaColumnName, dataType, converter, columnSize);
                lSql.getInitColumnCallback().onNewColumn(column);
                columns.put(javaColumnName, column);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private PreparedStatement setValuesInPreparedStatement(PreparedStatement ps,
                                                           List<String> columns, Row row) {
        try {
            for (int i = 0; i < columns.size(); i++) {
                Converter converter = column(columns.get(i)).getConverter();
                Column column = column(columns.get(i));
                converter.setValueInStatement(lSql, ps, i + 1, row.get(columns.get(i)), column.getSqlType());
            }
            return ps;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
