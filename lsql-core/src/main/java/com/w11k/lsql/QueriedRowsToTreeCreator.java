package com.w11k.lsql;

import com.google.common.base.Optional;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newLinkedList;

public class QueriedRowsToTreeCreator {

    public static <T extends Row> List<T> createResolvedTree(final List<String> ids, List<QueriedRow> queriedRows) {
        return create(true, ids, queriedRows);
    }

    public static List<Row> createViewTree(final List<String> ids, List<QueriedRow> queriedRows) {
        return create(false, ids, queriedRows);
    }

    private static <T extends Row> List<T> create(boolean useTableRowClassAndReplaceAliases,
                                        final List<String> ids,
                                        List<QueriedRow> queriedRows) {
        List<T> result = newLinkedList();

        final String id = getColumnName(ids.get(0));
        ids.remove(0);

        ListMultimap<Object, QueriedRow> byColumn = groupByColumn(queriedRows, id);
        for (Object key : byColumn.keySet()) {
            List<QueriedRow> rowsForKey = byColumn.get(key);
            QueriedRow root = rowsForKey.get(0);

            // Create copy
            Row copy = createRow(useTableRowClassAndReplaceAliases, root, id);

            if (!ids.isEmpty()) {
                String childId = getJoinedEntriesName(ids.get(0));
                List<Row> childs = create(useTableRowClassAndReplaceAliases, newLinkedList(ids), rowsForKey);
                copy.put(childId, childs);
            }
            addEntryToList(result, copy);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Row> void addEntryToList(List<T> result, Row copy) {
        result.add((T) copy);
    }

    private static Row createRow(boolean usePojoAndReplaceAliases, QueriedRow root, String id) {
        Map<String, ResultSetColumn<?>> rscs = root.getResultSetColumns();
        ResultSetColumn<?> rsc = rscs.get(id);
        Optional<? extends Table<?>> expectedTable = rsc.getColumn().getTable();

        Row row = usePojoAndReplaceAliases && expectedTable.isPresent()
                ? expectedTable.get().newRowPojoInstance()
                : new Row();

        // Check all columns
        for (String key : newLinkedList(root.keySet())) {
            // Only process columns that are in the result set (skip already created joins)
            if (rscs.containsKey(key)) {
                ResultSetColumn<?> resultSetColumn = rscs.get(key);
                Column<? extends Row> column = resultSetColumn.getColumn();
                Optional<? extends Table<?>> toCheck = column.getTable();

                // Store the column, if ...
                // ... it belongs to no table (e.g. a calculated value)
                // ... it belongs to the same table
                if (!toCheck.isPresent() || expectedTable.equals(toCheck)) {
                    Object value = root.get(key);
                    row.put(usePojoAndReplaceAliases ? column.getColumnName() : resultSetColumn.getName(), value);
                }
            }
        }

        return row;
    }

    /**
     * Remove columns with a different table source. Keep column if the table is unknown.
     */
    private static void removeColumnWithDifferentTable(QueriedRow row, String column) {
        Map<String, ResultSetColumn<?>> rscs = row.getResultSetColumns();
        ResultSetColumn<?> rsc = rscs.get(column);
        Optional<? extends Table<?>> expectedTable = rsc.getColumn().getTable();
        if (!expectedTable.isPresent()) {
            return;
        }
        for (String key : newLinkedList(row.keySet())) {
            if (!rscs.containsKey(key)) {
                // Remove joined entries
                row.remove(key);
            } else {
                Optional<? extends Table<?>> toCheck = rscs.get(key).getColumn().getTable();
                if (toCheck.isPresent() && !expectedTable.equals(toCheck)) {
                    row.remove(key);
                }
            }
        }
    }

    private static String getJoinedEntriesName(String s) {
        int index = s.indexOf(" as ");
        if (index == -1) {
            return s + "s";
        } else {
            return s.substring(index + 4, s.length());
        }
    }

    private static String getColumnName(String s) {
        int index = s.indexOf(" as ");
        if (index == -1) {
            return s;
        } else {
            return s.substring(0, index);
        }
    }

    private static ListMultimap<Object, QueriedRow> groupByColumn(List<QueriedRow> queriedRows,
                                                                  final String column) {
        LinkedListMultimap<Object, QueriedRow> result = LinkedListMultimap.create();
        for (QueriedRow row : queriedRows) {
            Object value = row.get(column);
            if (value != null) {
                result.put(value, row);
            }
        }
        return result;
    }

}