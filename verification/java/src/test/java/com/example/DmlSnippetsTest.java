package com.example;

import com.example.mapping.CourseTable;
import com.example.model.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import com.example.runtime.TenantFilter;
import org.babyfish.jimmer.sql.Associations;
import org.babyfish.jimmer.sql.DissociateAction;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.mutation.AffectedTable;
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode;
import org.babyfish.jimmer.sql.ast.mutation.DeleteResult;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.ast.table.AssociationTable;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.exception.SaveException;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class DmlSnippetsTest {

    private JSqlClient sqlClient;

    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jd;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection();
             InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) stmt.execute(part);
                }
                stmt.execute("create table course(id bigint primary key, name varchar(20) not null)");
            }
        }
        TenantFilter.CURRENT_TENANT.set("a");
        sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor())
                .build();
    }

    @Test
    public void deleteCommands() {
        DeleteResult r1 = sqlClient.deleteById(Book.class, 1L);
        assertEquals(1, r1.getAffectedRowCount(Book.class));
        assertEquals(2, r1.getAffectedRowCount(AffectedTable.of(BookProps.AUTHORS)));
        DeleteResult r2 = sqlClient.deleteByIds(Book.class, List.of(2L, 999L));
        assertEquals(1, r2.getAffectedRowCount(Book.class));

        // Author is logically deletable: AUTO -> UPDATE, PHYSICAL -> DELETE
        DeleteResult logical = sqlClient.deleteById(Author.class, 3L);
        assertEquals(1, logical.getAffectedRowCount(Author.class));
        assertNull(sqlClient.findById(Author.class, 3L));
        sqlClient.deleteById(Author.class, 3L, DeleteMode.PHYSICAL);

        DeleteResult r4 = sqlClient.getEntities()
                .deleteCommand(BookStore.class, 1L)
                .setDissociateAction(BookProps.STORE, DissociateAction.SET_NULL)
                .execute();
        assertEquals(1, r4.getAffectedRowCount(BookStore.class));
        assertTrue(r4.getAffectedRowCount(AffectedTable.of(Book.class)) >= 1);   // FKs set to null
        assertTrue(r4.getTotalAffectedRowCount() >= 2);
        sqlClient.getEntities().deleteAll(Book.class, List.of(3L, 4L), DeleteMode.AUTO);
        sqlClient.getEntities().deleteAllCommand(Book.class, List.of(5L)).setMode(DeleteMode.PHYSICAL).execute();
        assertEquals(0, sqlClient.deleteById(Book.class, 12345L).getTotalAffectedRowCount());

        // CHECK dissociation throws
        assertThrows(SaveException.CannotDissociateTarget.class, () ->
                sqlClient.getEntities().deleteCommand(BookStore.class, 2L)
                        .setDissociateAction(BookProps.STORE, DissociateAction.CHECK)
                        .execute());
        // TreeNode.parent is DELETE: recursive delete
        DeleteResult tree = sqlClient.deleteById(TreeNode.class, 1L);
        assertEquals(9, tree.getAffectedRowCount(TreeNode.class));
    }

    @Test
    public void updateAndDeleteStatements() {
        AuthorTable author = AuthorTable.$;
        int count = sqlClient.createUpdate(author)
                .set(author.firstName(), author.firstName().concat("*"))
                .set(author.gender(), Gender.MALE)
                .where(author.firstName().eq("Dan"))
                .execute();
        assertEquals(1, count);

        List<Tuple2<Long, String>> rows = sqlClient.createUpdate(author)
                .set(author.firstName(), "Daniel")
                .where(author.firstName().eq("Dan*"))
                .returning(author.id(), author.firstName())
                .execute();
        assertEquals("Daniel", rows.get(0).get_2());
        try (Stream<Tuple2<Long, String>> s = sqlClient.createUpdate(author)
                .set(author.firstName(), "Dan")
                .where(author.firstName().eq("Daniel"))
                .returning(author.id(), author.firstName())
                .stream()) {
            assertEquals(1, s.count());
        }

        // logically deleted rows are not updated by statements
        sqlClient.deleteById(Author.class, 3L);
        assertEquals(0, sqlClient.createUpdate(author).set(author.lastName(), "X").where(author.id().eq(3L)).execute());
        assertEquals(0, sqlClient.filters(cfg -> cfg.disableAll())   // user filters only
                .createUpdate(author).set(author.lastName(), "X").where(author.id().eq(3L)).execute());
        assertEquals(1, sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))
                .createUpdate(author).set(author.lastName(), "X").where(author.id().eq(3L)).execute());

        BookTable book = BookTable.$;
        int n = sqlClient.createDelete(book)
                .where(book.name().eq("Effective TypeScript"))
                .execute();
        assertEquals(2, n);       // 1 BOOK row + 1 BOOK_AUTHOR_MAPPING row
        int byJoin = sqlClient.createDelete(book)
                .where(book.store().name().eq("MANNING"))
                .setMode(DeleteMode.PHYSICAL)
                .execute();
        assertEquals(4, byJoin);  // 2 books + 2 links
        assertEquals(0, sqlClient.createDelete(book).where(book.edition().gt(5)).disableDissociation().execute());

        // logical delete by statement
        int logicallyDeleted = sqlClient.createDelete(author).where(author.firstName().eq("Eve")).execute();
        assertEquals(1, logicallyDeleted);
        assertTrue(sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))
                .createQuery(author).where(author.firstName().eq("Eve")).select(author.deleted()).execute().get(0));
    }

    @Test
    public void insertFromSelect() {
        CourseRowTable source = sqlClient.createBaseQuery()
                .select(CourseRowMapper.id(Expression.value(100L)).name(Expression.value("Math")))
                .asBaseTable();

        CourseTable course = CourseTable.$;
        int inserted = sqlClient.createInsert(course, source)
                .set(course.id(), source.getId())
                .set(course.name(), source.getName())
                .onConflictDoNothing(course.id())
                .execute();
        assertEquals(1, inserted);
        assertEquals(0, sqlClient.createInsert(course, source)
                .set(course.id(), source.getId())
                .set(course.name(), source.getName())
                .onConflictDoNothing()
                .execute());

        CourseRowTable source2 = sqlClient.createBaseQuery()
                .select(CourseRowMapper.id(Expression.value(100L)).name(Expression.value("Physics")))
                .asBaseTable();
        List<Tuple2<Long, String>> upserted = sqlClient.createUpsert(course, source2)
                .key(course.id(), source2.getId())
                .merge(course.name(), source2.getName())
                .updateWhere(course.name().ne(source2.getName()))
                .returning(course.id(), course.name())
                .execute();
        assertEquals(List.of(new Tuple2<>(100L, "Physics")), upserted);

        // entity query as source for an entity target
        BookStoreTable store = BookStoreTable.$;
        BookStoreTable selected = sqlClient.createBaseQuery(store)
                .where(store.name().eq("MANNING"))
                .select(store)
                .asBaseTable();
        assertEquals(1, sqlClient.createInsert(course, selected)
                .set(course.id(), selected.id())
                .set(course.name(), selected.name())
                .execute());

        // middle table: rootless source only (entity-rooted sources throw in 0.12.4)
        BookAuthorPairTable pairs = sqlClient.createBaseQuery()
                .select(BookAuthorPairMapper.bookId(Expression.value(4L)).authorId(Expression.value(1L)))
                .asBaseTable();
        AssociationTable<Book, BookTableEx, Author, AuthorTableEx> assoc =
                AssociationTable.of(BookTableEx.class, BookTableEx::authors);
        int links = sqlClient.createInsert(assoc, pairs)
                .set(assoc.<Long>sourceId(), pairs.getBookId())
                .set(assoc.<Long>targetId(), pairs.getAuthorId())
                .onConflictDoNothing()
                .execute();
        assertEquals(1, links);
    }

    @Test
    public void associations() {
        Associations bookAuthors = sqlClient.getAssociations(BookProps.AUTHORS);
        assertEquals(1, bookAuthors.insert(4L, 1L));
        assertEquals(0, bookAuthors.insertIfAbsent(4L, 1L));
        assertEquals(2, bookAuthors.insertAll(List.of(5L, 6L), List.of(1L)));
        bookAuthors.insertAllIfAbsent(List.of(new Tuple2<>(5L, 1L), new Tuple2<>(5L, 2L)));
        bookAuthors.replace(5L, 3L);
        assertEquals(List.of(3L), sqlClient.findById(BookFetcher.$.authorIds(), 5L).authorIds());
        assertEquals(1, bookAuthors.delete(5L, 3L));
        bookAuthors.deleteAll(List.of(4L, 6L), List.of(1L, 3L));
        bookAuthors.saveAllCommand(List.of(4L), List.of(1L, 2L)).ignoreConflict(true).execute();
        Associations authorBooks = bookAuthors.reverse();
        assertEquals(0, authorBooks.insertIfAbsent(1L, 4L));

        sqlClient.saveCommand(Immutables.createBook(d -> d.setId(4L).setAuthorIds(List.of(1L, 3L))))
                .setMode(SaveMode.UPDATE_ONLY)
                .execute();
        assertEquals(java.util.Set.of(1L, 3L), new java.util.HashSet<>(sqlClient.findById(BookFetcher.$.authorIds(), 4L).authorIds()));
    }
}
