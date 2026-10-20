package com.example

import com.example.model.*
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.table.KNonNullTable
import org.babyfish.jimmer.sql.kt.ast.table.KWeakJoin

class BookAuthorJoin : KWeakJoin<Book, Author>() {
    override fun on(source: KNonNullTable<Book>, target: KNonNullTable<Author>): KNonNullExpression<Boolean> =
        source.name eq target.lastName
}
