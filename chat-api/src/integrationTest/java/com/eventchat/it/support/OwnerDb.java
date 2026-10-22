package com.eventchat.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 소유자 계정으로 DB를 직접 다룬다. 앱 계정으로는 할 수 없는 조작(이벤트를 일부러 망가뜨리기, 버전 값 심기)과
 * 앱 계정 권한 확인에 쓴다.
 */
public final class OwnerDb {

    private OwnerDb() {
    }

    public static int update(String sql) {
        try (Connection connection = owner(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public static String scalar(String sql) {
        try (Connection connection = owner(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** 앱 계정으로 실행해 본다. 권한이 없으면 SQLException을 그대로 던진다. */
    public static void asApp(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(ChatContainers.jdbcUrl(), ChatContainers.DB_APP,
                ChatContainers.DB_APP_PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(ChatContainers.jdbcUrl(), ChatContainers.DB_OWNER,
                ChatContainers.DB_OWNER_PASSWORD);
    }
}
