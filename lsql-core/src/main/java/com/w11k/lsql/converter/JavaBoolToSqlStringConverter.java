package com.w11k.lsql.converter;

import com.google.common.base.Optional;
import com.w11k.lsql.LSql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static com.google.common.base.Optional.of;
import static com.google.common.base.Preconditions.checkArgument;

public class JavaBoolToSqlStringConverter extends Converter {

    private final String sqlStringValueForTrue;

    private final String sqlStringValueForFalse;

    public JavaBoolToSqlStringConverter(String sqlStringValueForTrue,
                                        String sqlStringValueForFalse) {
        checkArgument(!sqlStringValueForTrue.equals(sqlStringValueForFalse));
        this.sqlStringValueForTrue = sqlStringValueForTrue;
        this.sqlStringValueForFalse = sqlStringValueForFalse;
    }

    @Override
    public int[] getSupportedSqlTypes() {
        return new int[]{Types.VARCHAR, Types.CHAR};
    }

    @Override
    public Object getValue(LSql lSql, ResultSet rs, int index) throws SQLException {
        String val = rs.getString(index);
        if (val.equals(sqlStringValueForTrue)) {
            return true;
        } else if (val.equals(sqlStringValueForFalse)) {
            return false;
        } else {
            throw new IllegalArgumentException("Value must be "
                    + "'" + sqlStringValueForTrue + "' for yes and "
                    + "'" + sqlStringValueForFalse + "' for false.");
        }
    }

    @Override
    public void setValue(LSql lSql, PreparedStatement ps, int index,
                                    Object val) throws SQLException {
        String yesOrNo = ((Boolean) val) ? sqlStringValueForTrue : sqlStringValueForFalse;
        ps.setString(index, yesOrNo);
    }

    @Override
    public Optional<? extends Class<?>> getSupportedJavaClass() {
        return of(Boolean.class);
    }
}
