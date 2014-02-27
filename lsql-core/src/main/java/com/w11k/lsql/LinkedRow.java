package com.w11k.lsql;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.w11k.lsql.converter.Converter;
import com.w11k.lsql.validation.AbstractValidationError;
import org.codehaus.jackson.map.ObjectMapper;

import java.util.Map;

public class LinkedRow<P extends Row> extends Row {

    private Table<P> table;

    public LinkedRow(Table<P> table) {
        this(table, Maps.<String, Object>newLinkedHashMap());
    }

    public LinkedRow(Table<P> table, Map<String, Object> row) {
        super(row);
        this.table = table;
    }

    public Table<P> getTable() {
        return table;
    }

    public void setTable(Table<P> table) {
        this.table = table;
    }

    /**
     * @return the primary key value
     */
    public Object getId() {
        return get(table.getPrimaryKeyColumn().get());
    }

    /**
     * @return the revision value
     */
    public Object getRevision() {
        return get(table.getRevisionColumn().get().getColumnName());
    }

    /**
     * Convenience method to set the expected revision.
     *
     * @param revision Revision to use for DML statements.
     */
    public void setRevision(Object revision) {
        put(table.getRevisionColumn().get().getColumnName(), revision);
    }

    /**
     * Removes the primary column and revision column value, if existent.
     */
    public void removeIdAndRevision() {
        remove(table.getPrimaryKeyColumn().get());
        if (table.getRevisionColumn().isPresent()) {
            remove(table.getRevisionColumn().get().getColumnName());
        }
    }

    /**
     * Puts the given key/value pair into this instance and calls
     * {@link com.w11k.lsql.Table#validate(String, Object)}.
     */
    @Override
    public Object put(String key, Object value) {
        Optional<? extends AbstractValidationError> validate = table.validate(key, value);
        if (validate.isPresent()) {
            validate.get().throwError();
        }
        return super.put(key, value);
    }

    /**
     * Puts all known entries into this instance. Tries to convert values with wrong type.
     */
    public LinkedRow<P> putAllKnown(Row from) {
        for (String key : from.keySet()) {
            if (table.getColumns().containsKey(key)) {
                Object val = from.get(key);

                Converter converter = table.getColumns().get(key).getConverter();
                Optional<? extends Class<?>> supportedJavaClass = converter.getSupportedJavaClass();
                if (supportedJavaClass.isPresent() && !supportedJavaClass.get().equals(val)) {
                    val = converter.convertValueToTargetType(val);
                }

                put(key, val);
            }
        }
        return this;
    }

    /**
     * Delegates to {@link Table#insert(Row)}.
     */
    public Optional<?> insert() {
        return table.insert(this);
    }

    /**
     * Delegates to {@link Table#save(Row)}.
     */
    public Optional<?> save() {
        return table.save(this);
    }

    /**
     * Delegates to {@link Table#delete(Row)}.
     */
    public void delete() {
        Object id = get(table.getPrimaryKeyColumn().get());
        if (id == null) {
            throw new IllegalStateException("Can not delete this LinkedRow because the primary key value is not present.");
        }
        table.delete(this);
    }

    public P toPojo() {
        return table.rowToPojo(this);
    }

    @Override
    protected ObjectMapper getObjectMapper() {
        return table.getlSql().getObjectMapper();
    }

}
