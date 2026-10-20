package com.example.runtime

import com.example.model.common.BaseEntity
import com.example.model.common.BaseEntityDraft
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.sql.DraftInterceptor
import java.time.LocalDateTime

class BaseEntityDraftInterceptor : DraftInterceptor<BaseEntity, BaseEntityDraft> {

    override fun beforeSave(draft: BaseEntityDraft, original: BaseEntity?) {
        val now = LocalDateTime.now()
        if (!isLoaded(draft, BaseEntity::modifiedTime)) {
            draft.modifiedTime = now
        }
        if (original === null && !isLoaded(draft, BaseEntity::createdTime)) {
            draft.createdTime = now
        }
    }
}
