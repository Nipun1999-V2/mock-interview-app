package com.nipun.mockinterview.db

import com.nipun.mockinterview.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    /**
     * Returns the connected Exposed [Database] handle (not just the raw
     * DataSource) so callers - notably [SessionRepository] - can pass it
     * explicitly into every transaction instead of relying on Exposed's
     * implicit "last connected database" global, which is fragile once
     * more than one Database gets connected in the same JVM (as tests do).
     */
    fun init(config: AppConfig): Database {
        val dataSource = hikari(config)
        val database = Database.connect(dataSource)
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(Sessions, SessionTurns)
        }
        return database
    }

    private fun hikari(config: AppConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        return HikariDataSource(hikariConfig)
    }
}
