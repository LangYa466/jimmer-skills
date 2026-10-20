package com.example

import com.example.runtime.BaseEntityDraftInterceptor
import com.example.runtime.TenantDraftInterceptor
import com.example.runtime.TenantFilter
import org.babyfish.jimmer.sql.dialect.H2Dialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.cfg.KSqlClientDsl
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor
import org.h2.jdbcx.JdbcDataSource

object TestSupport {
    fun dataSource(db: String, vararg extraSql: String): JdbcDataSource {
        val ds = JdbcDataSource().apply { setURL("jdbc:h2:mem:$db;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true") }
        ds.connection.use { con ->
            val sql = TestSupport::class.java.getResourceAsStream("/schema.sql")!!.readBytes().toString(Charsets.UTF_8)
            con.createStatement().use { stmt ->
                sql.split(";").filter { it.isNotBlank() }.forEach { stmt.execute(it) }
                extraSql.forEach { stmt.execute(it) }
            }
        }
        return ds
    }

    fun client(db: String, vararg extraSql: String, tenantFilter: Boolean = false, block: KSqlClientDsl.() -> Unit = {}): KSqlClient {
        val ds = dataSource(db, *extraSql)
        TenantFilter.CURRENT_TENANT.set("a")
        return newKSqlClient {
            setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
            setDialect(H2Dialect())
            setExecutor(Executor.log())
            addDraftInterceptors(BaseEntityDraftInterceptor(), TenantDraftInterceptor())
            if (tenantFilter) addFilters(TenantFilter())
            block()
        }
    }
}
