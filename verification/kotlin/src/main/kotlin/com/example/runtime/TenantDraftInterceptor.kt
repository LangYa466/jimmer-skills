package com.example.runtime

import com.example.model.common.TenantAware
import com.example.model.common.TenantAwareDraft
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.sql.DraftInterceptor

class TenantDraftInterceptor : DraftInterceptor<TenantAware, TenantAwareDraft> {

    override fun beforeSave(draft: TenantAwareDraft, original: TenantAware?) {
        if (!isLoaded(draft, TenantAware::tenant)) {
            draft.tenant = TenantFilter.CURRENT_TENANT.get()
        }
    }
}
