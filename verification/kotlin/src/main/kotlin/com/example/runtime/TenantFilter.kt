package com.example.runtime

import com.example.model.common.TenantAware
import com.example.model.common.tenant
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.filter.KFilter
import org.babyfish.jimmer.sql.kt.filter.KFilterArgs

class TenantFilter : KFilter<TenantAware> {

    override fun filter(args: KFilterArgs<TenantAware>) {
        CURRENT_TENANT.get()?.let {
            args.apply {
                where(table.tenant eq it)
            }
        }
    }

    companion object {
        val CURRENT_TENANT: ThreadLocal<String> = ThreadLocal.withInitial { "a" }
    }
}
