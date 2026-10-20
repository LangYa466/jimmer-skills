package com.example.runtime;

import com.example.model.common.TenantAwareProps;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.FilterArgs;

public class TenantFilter implements Filter<TenantAwareProps> {

    public static final ThreadLocal<String> CURRENT_TENANT = ThreadLocal.withInitial(() -> "a");

    @Override
    public void filter(FilterArgs<TenantAwareProps> args) {
        String tenant = CURRENT_TENANT.get();
        if (tenant != null) {
            args.where(args.getTable().tenant().eq(tenant));
        }
    }
}
