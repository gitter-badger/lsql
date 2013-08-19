package com.w11k.lsql.utils;

import com.w11k.lsql.LSql;
import com.w11k.lsql.exceptions.DatabaseAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class ConnectionUtils {

    public static Connection getConnection(LSql lSql) {
        try {
            return lSql.getConnectionFactory().call();
        } catch (Exception e) {
            throw new DatabaseAccessException(e);
        }
    }

    public static Statement createStatement(LSql lSql) {
        try {
            return getConnection(lSql).createStatement();
        } catch (SQLException e) {
            throw new DatabaseAccessException(e);
        }
    }

    public static PreparedStatement prepareStatement(LSql lSql, String sqlString) {
        try {
            return getConnection(lSql).prepareStatement(sqlString, Statement.RETURN_GENERATED_KEYS);
        } catch (SQLException e) {
            throw new DatabaseAccessException(e);
        }
    }

}
