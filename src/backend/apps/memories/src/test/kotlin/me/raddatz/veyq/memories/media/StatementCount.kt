package me.raddatz.veyq.memories.media

import io.quarkus.arc.Unremovable
import io.quarkus.hibernate.orm.PersistenceUnitExtension
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the SQL statements Hibernate prepares while armed — the query-count tests' meter (cr-gallery-list-paging). The
 * persistence unit takes it as its statement inspector (`@PersistenceUnitExtension`), so it sees every statement of
 * this module's test boots, and counts only between [StatementCount.during]'s arm and disarm. The scheduler is halted
 * in `%test` (`quarkus.scheduler.start-mode=halted`), so nothing but the measured request runs while it is armed.
 */
@PersistenceUnitExtension
@ApplicationScoped
@Unremovable
class CountingStatementInspector : StatementInspector {
    override fun inspect(sql: String): String {
        if (StatementCount.armed) StatementCount.count.incrementAndGet()
        return sql
    }
}

object StatementCount {
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
