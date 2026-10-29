package me.raddatz.veyq.core.persistence

import io.quarkus.arc.Unremovable
import io.quarkus.hibernate.orm.PersistenceUnitExtension
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the SQL statements Hibernate prepares while armed — `:core`'s query-count tests' meter (inbox #537), the shape of
 * Memories' `StatementCount`: a module's test sources are its own, so each module that counts carries one. The
 * persistence unit takes it as its statement inspector (`@PersistenceUnitExtension`), so it sees every statement of this
 * module's test boots, and counts only between [CoreStatementCount.during]'s arm and disarm. The scheduler is halted in
 * `%test` (`quarkus.scheduler.start-mode=halted`), so nothing but the measured request runs while it is armed.
 */
@PersistenceUnitExtension
@ApplicationScoped
@Unremovable
class CountingStatementInspector : StatementInspector {
    override fun inspect(sql: String): String {
        if (CoreStatementCount.armed) CoreStatementCount.count.incrementAndGet()
        return sql
    }
}

object CoreStatementCount {
    @Volatile
    internal var armed = false
    internal val count = AtomicInteger()

    /** The statements prepared while [block] ran (its HTTP request included: the request thread is another thread). */
    fun during(block: () -> Unit): Int {
        count.set(0)
        armed = true
        try {
            block()
        } finally {
            armed = false
        }
        return count.get()
    }
}
