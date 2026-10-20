package com.example.graphql;

import com.example.dto.BookInput;
import com.example.model.Book;
import com.example.model.BookTable;
import graphql.schema.DataFetchingEnvironment;
import org.babyfish.jimmer.spring.graphql.DataFetchingEnvironments;
import org.babyfish.jimmer.sql.JSqlClient;
import org.jetbrains.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Controller
public class BookGraphQLController {

    private final JSqlClient sqlClient;

    public BookGraphQLController(JSqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @QueryMapping
    public List<Book> books(@Argument @Nullable String name, DataFetchingEnvironment env) {
        BookTable table = BookTable.$;
        return sqlClient.createQuery(table)
                .where(table.name().ilikeIf(name))
                .orderBy(table.name().asc(), table.edition().desc())
                .select(table.fetch(DataFetchingEnvironments.createFetcher(Book.class, env)))   // only requested scalars
                .execute();
    }

    @QueryMapping
    public List<Book> orphanBooks() {                       // associations are still resolved by Jimmer's batch loaders
        BookTable table = BookTable.$;
        return sqlClient.createQuery(table).orderBy(table.id()).select(table).execute();
    }

    @MutationMapping
    @Transactional
    public Book saveBook(@Argument BookInput input) {
        return sqlClient.save(input).getModifiedEntity();
    }
}
