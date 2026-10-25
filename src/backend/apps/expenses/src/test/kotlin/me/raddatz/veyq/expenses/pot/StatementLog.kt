package me.raddatz.veyq.expenses.pot

import io.quarkus.arc.Unremovable
import io.quarkus.hibernate.orm.PersistenceUnitExtension
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.Collections

/**
 * Records the SQL statements Hibernate prepares while armed — this module's query-log tests' meter (Memories'
 * `StatementCount` shape). The persistence unit takes it as its statement inspector (`@PersistenceUnitExtension`), so it
 * sees every statement of this module's test boots, and records only between [StatementLog.during]'s arm and disarm.
 * The scheduler is halted in `%test`, so nothing but the measured request runs while it is armed.
 */
@PersistenceUnitExtension
@ApplicationScoped
@Unremovable
class RecordingStatementInspector : StatementInspector {
    override fun inspect(sql: String): String {
        if (StatementLog.armed) StatementLog.statements.add(sql)
        return sql
    }
}

object StatementLog {
    @Volatile
    internal var armed = false
    internal val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The statements prepared while [block] ran (its HTTP request included: the request thread is another thread). */
    fun during(block: () -> Unit): List<String> {
        statements.clear()
        armed = true
        try {
            block()
        } finally {
            armed = false
        }
        return synchronized(statements) { statements.toList() }
    }
}
