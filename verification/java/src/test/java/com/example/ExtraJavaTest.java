package com.example;

import com.example.model.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import com.example.runtime.TenantFilter;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.JoinType;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.Predicate;
import org.babyfish.jimmer.sql.ast.query.ConfigurableRootQuery;
import org.babyfish.jimmer.sql.ast.table.AssociationTable;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.association.Association;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.DefaultDatabaseNamingStrategy;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ExtraJavaTest {

    private JSqlClient sqlClient;

    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jx;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection();
             InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) {
                        stmt.execute(part);
                    }
                }
            }
        }
        sqlClient = JSqlClient
                .newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect())
                .setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.UPPER_CASE)
                .setExecutor(Executor.log())
                .setDefaultBatchSize(128)
                .setDefaultListBatchSize(16)
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor())
                .addFilters(new TenantFilter())
                .build();
    }

    @Test
    public void testFindByIdIgnoresFilters() {
        sqlClient.deleteById(Author.class, 3L); // logical delete
        AuthorTable author = Tables.AUTHOR_TABLE;
        assertNull(sqlClient.createQuery(author).where(author.id().eq(3L)).select(author).fetchOneOrNull());
        Author byId = sqlClient.findById(Author.class, 3L);
        System.out.println("findById after logical delete: " + byId);
        // tenant filter: book 2 belongs to tenant b, current tenant is a
        Book other = sqlClient.findById(Book.class, 2L);
        System.out.println("findById other tenant: " + other);
    }

    @Test
    public void testMisc() {
        BookTable table = Tables.BOOK_TABLE;
        String name = null;
        List<Book> books = sqlClient
                .createQuery(table)
                .whereIf(name != null && !name.isEmpty(), () -> table.name().ilike(name))
                .whereIf(true, table.edition().ge(1))
                .orderByIf(true, table.price().desc())
                .select(table)
                .execute();
        assertFalse(books.isEmpty());

        // left join through reference
        List<Tuple2<String, String>> rows = sqlClient
                .createQuery(table)
                .select(table.name(), table.store(JoinType.LEFT).name())
                .execute();
        assertFalse(rows.isEmpty());

        // native predicate
        AuthorTable author = Tables.AUTHOR_TABLE;
        List<Author> authors = sqlClient
                .createQuery(author)
                .where(
                        Predicate.sql(
                                "lower(%e) = %v",
                                it -> it.expression(author.firstName()).value("eve")
                        )
                )
                .select(author)
                .execute();
        assertEquals(1, authors.size());

        // count of query
        ConfigurableRootQuery<BookTable, Book> query = sqlClient
                .createQuery(table)
                .where(table.price().gt(BigDecimal.ONE))
                .orderBy(table.name())
                .select(table);
        long total = query.fetchUnlimitedCount();
        assertEquals(3, total);
        List<Book> limited = query.limit(1, 1).execute();
        assertEquals(1, limited.size());

        // stream must be closed
        try (Stream<String> names = sqlClient
                .createQuery(table)
                .select(table.name())
                .stream()) {
            assertTrue(names.count() > 0);
        }

        // map
        List<String> mapped = sqlClient
                .createQuery(table)
                .select(table.name(), table.edition())
                .map(t -> t.get_1() + "#" + t.get_2());
        assertFalse(mapped.isEmpty());

        // association table query
        AssociationTable<Book, BookTableEx, Author, AuthorTableEx> association =
                AssociationTable.of(BookTableEx.class, BookTableEx::authors);
        List<Association<Book, Author>> associations = sqlClient
                .createQuery(association)
                .where(association.source().id().eq(1L))
                .select(association)
                .execute();
        assertEquals(2, associations.size());

        // association API
        int inserted = sqlClient.getAssociations(BookProps.AUTHORS).insertIfAbsent(4L, 1L);
        assertEquals(1, inserted);
        int deleted = sqlClient.getAssociations(BookProps.AUTHORS).delete(4L, 1L);
        assertEquals(1, deleted);

        // Tuple2.toMap
        Map<Long, BigDecimal> map = Tuple2.toMap(
                sqlClient
                        .createQuery(table)
                        .select(table.id(), table.price())
                        .execute()
        );
        assertEquals(3, map.size());

        // disable filters for one query
        long all = sqlClient
                .filters(cfg -> cfg.disableByTypes(TenantFilter.class))
                .createQuery(table)
                .select(table.count())
                .fetchOne();
        assertEquals(6, all);

        // forUpdate / distinct / exists through java API
        List<Long> locked = sqlClient
                .createQuery(table)
                .where(table.id().eq(1L))
                .select(table.id())
                .forUpdate()
                .execute();
        assertEquals(1, locked.size());

        List<Integer> editions = sqlClient
                .createQuery(table)
                .select(Expression.constant(1))
                .execute();
        assertFalse(editions.isEmpty());

        // findAll with sorted props through entities
        List<Book> sorted = sqlClient.getEntities().findAll(
                BookFetcher.$.name().edition(),
                BookProps.NAME,
                BookProps.EDITION.desc()
        );
        assertFalse(sorted.isEmpty());
    }
}
