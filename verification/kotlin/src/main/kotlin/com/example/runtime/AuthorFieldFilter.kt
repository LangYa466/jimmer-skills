package com.example.runtime

import com.example.model.Author
import com.example.model.firstName
import com.example.model.lastName
import org.babyfish.jimmer.sql.kt.ast.expression.ilike
import org.babyfish.jimmer.sql.kt.fetcher.KFieldFilter
import org.babyfish.jimmer.sql.kt.fetcher.KFieldFilterDsl

class AuthorFieldFilter : KFieldFilter<Author> {
    override fun KFieldFilterDsl<Author>.applyTo() {
        where(table.firstName ilike "a")
        orderBy(table.lastName)
    }
}
