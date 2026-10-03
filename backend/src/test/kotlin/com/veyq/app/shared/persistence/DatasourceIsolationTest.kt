package com.veyq.app.shared.persistence

import io.agroal.api.AgroalDataSource
import io.agroal.api.configuration.AgroalConnectionFactoryConfiguration.TransactionIsolation
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Every connection runs READ COMMITTED, the isolation the slice tests prove (ADR 0078). The H2 the suite runs is
 * READ COMMITTED by default, so a live connection alone could not tell the line in `application.properties` from
 * its absence: the pool's own configuration is what reds when the line goes. The connection is asked as well, for
 * the run on the production engine (`scripts/run_backend_races_on_mariadb.py`), whose default is REPEATABLE READ.
 */
@QuarkusTest
class DatasourceIsolationTest {

    @Inject
    lateinit var dataSource: AgroalDataSource

    @Test
    fun everyConnectionReadsCommittedData() {
        val configured = dataSource.configuration.connectionPoolConfiguration().connectionFactoryConfiguration()
            .jdbcTransactionIsolation()
        assertEquals(TransactionIsolation.READ_COMMITTED, configured, "the pool must set READ COMMITTED on every connection")
        dataSource.connection.use {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, it.transactionIsolation, "the connection's isolation")
        }
    }
}
