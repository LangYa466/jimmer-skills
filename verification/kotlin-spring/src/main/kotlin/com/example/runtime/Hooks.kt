package com.example.runtime

import com.example.model.Book
import com.example.model.BookDraft
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.sql.DraftInterceptor
import org.babyfish.jimmer.sql.kt.cfg.KCustomizer
import org.babyfish.jimmer.sql.kt.cfg.KInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class Hooks {

    @Bean
    fun jimmerCustomizer() = KCustomizer { dsl ->
        dsl.setDefaultBatchSize(64)
        CUSTOMIZED.incrementAndGet()
    }

    @Bean
    fun jimmerInitializer() = KInitializer { sqlClient ->
        sqlClient.triggers                                   // e.g. register listeners
        INITIALIZED.incrementAndGet()
    }

    companion object {
        val CUSTOMIZED = AtomicInteger()
        val INITIALIZED = AtomicInteger()
    }
}

@Component
class BookDraftInterceptor : DraftInterceptor<Book, BookDraft> {
    override fun beforeSave(draft: BookDraft, original: Book?) {
        if (!isLoaded(draft, Book::modifiedTime)) {
            draft.modifiedTime = LocalDateTime.now()
        }
    }
}
