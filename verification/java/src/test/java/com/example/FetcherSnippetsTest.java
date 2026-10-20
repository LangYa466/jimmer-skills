package com.example;

import com.example.dto.BookView;
import com.example.model.*;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.IdOnlyFetchType;
import org.babyfish.jimmer.sql.fetcher.compiler.FetcherCompiler;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FetcherSnippetsTest {

    private JSqlClient sqlClient;

    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jf;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection(); InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) if (!part.isBlank()) stmt.execute(part);
            }
        }
        sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .build();
    }

    @Test
    public void shapes() {
        Author author = sqlClient.findById(AuthorFetcher.$.allScalarFields(), 1L);
        assertTrue(ImmutableObjects.isLoaded(author, AuthorProps.FIRST_NAME));
        assertFalse(ImmutableObjects.isLoaded(author, AuthorProps.DELETED));        // logical deleted flag excluded
        assertFalse(ImmutableObjects.isLoaded(author, AuthorProps.SQL_FULL_NAME));  // formula excluded
        assertTrue(ImmutableObjects.isLoaded(author, AuthorProps.FULL_NAME));       // java formula: computable from loaded deps
        System.out.println("AUTHOR JSON: " + author);
        assertFalse(author.toString().contains("fullName"));                         // ...but hidden unless fetched

        Book allTable = sqlClient.findById(BookFetcher.$.allTableFields(), 1L);
        assertTrue(ImmutableObjects.isIdOnly(allTable.store()));

        BookTable t = BookTable.$;
        Fetcher<Book> nullFetcher = null;
        Book viaNull = sqlClient.createQuery(t).where(t.id().eq(1L)).select(t.fetch(nullFetcher)).fetchOne();
        assertTrue(ImmutableObjects.isLoaded(viaNull, BookProps.PRICE));
        assertTrue(ImmutableObjects.isLoaded(viaNull, BookProps.STORE));

        Book limited = sqlClient.findById(
                BookFetcher.$.name()
                        .store(IdOnlyFetchType.RAW)
                        .authors(AuthorFetcher.$.firstName(), cfg -> cfg.batch(1).limit(1, 0)
                                .filter(args -> { args.where(args.getTable().firstName().ilike("e")); args.orderBy(args.getTable().lastName()); })),
                1L
        );
        assertEquals(1, limited.authors().size());

        Fetcher<Book> compiled = FetcherCompiler.compile("{ name store { name } authors { firstName } }", Book.class);
        Book c = sqlClient.findById(compiled, 1L);
        assertNotNull(c.store().name());

        List<BookView> views = sqlClient.findByIds(BookView.class, List.of(1L, 3L));
        assertEquals(2, views.size());
    }
}
