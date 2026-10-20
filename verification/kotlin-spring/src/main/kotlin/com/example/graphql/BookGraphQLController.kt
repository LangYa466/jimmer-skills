package com.example.graphql

import com.example.dto.BookInput
import com.example.model.*
import graphql.schema.DataFetchingEnvironment
import org.babyfish.jimmer.spring.graphql.toFetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.`ilike?`
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class BookGraphQLController(private val sqlClient: KSqlClient) {

    @QueryMapping
    fun books(@Argument name: String?, env: DataFetchingEnvironment): List<Book> =
        sqlClient.createQuery(Book::class) {
            where(table.name `ilike?` name)
            orderBy(table.name.asc(), table.edition.desc())
            select(table.fetch(env.toFetcher<Book>()))       // only the requested scalars
        }.execute()

    @QueryMapping
    fun orphanBooks(): List<Book> =                           // associations resolved by Jimmer's batch loaders
        sqlClient.createQuery(Book::class) {
            orderBy(table.id)
            select(table)
        }.execute()

    @MutationMapping
    @Transactional
    fun saveBook(@Argument input: BookInput): Book =
        sqlClient.save(input).modifiedEntity
}
