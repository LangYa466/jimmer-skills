package com.example.runtime;

import com.example.model.common.TenantAware;
import com.example.model.common.TenantAwareDraft;
import com.example.model.common.TenantAwareProps;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.sql.DraftInterceptor;
import org.jetbrains.annotations.Nullable;

public class TenantDraftInterceptor implements DraftInterceptor<TenantAware, TenantAwareDraft> {

    @Override
    public void beforeSave(TenantAwareDraft draft, @Nullable TenantAware original) {
        if (!ImmutableObjects.isLoaded(draft, TenantAwareProps.TENANT)) {
            draft.setTenant(TenantFilter.CURRENT_TENANT.get());
        }
    }
}
