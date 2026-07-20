package dev.saicoremake.headhunting.storage;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
}
