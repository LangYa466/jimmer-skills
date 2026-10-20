package com.example.behavior;

import com.example.Immutables;
import com.example.model.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.ast.mutation.SimpleSaveResult;
import org.babyfish.jimmer.sql.ast.table.AssociationTable;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.exception.SaveException;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Surprising behaviors of Jimmer 0.12.4 that the skills document.
 * If one of these tests starts failing after a Jimmer upgrade, update the skills.
 */
public class KnownBehaviorTest {

    private JSqlClient client(String db, boolean interceptors) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection(); InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) if (!part.isBlank()) stmt.execute(part);
            }
        }
        JSqlClient.Builder builder = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect());
        if (interceptors) {
            builder.addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor());
        }
        return builder.build();
    }

    @Test
    public void constantOnTheLeftUsesNegatedOperator() throws Exception {
        JSqlClient sqlClient = client("kb1", false);
        BookTable t = BookTable.$;                     // book 1 costs 50.00
        BigDecimal fifty = new BigDecimal("50.00");
        // 50 > 50 is false, but Jimmer renders PRICE <= 50
        assertFalse(sqlClient.createQuery(t).where(t.id().eq(1L))
                .where(Expression.value(fifty).gt(t.price())).select(t.id()).execute().isEmpty());
        // 50 < 50 is false, but Jimmer renders PRICE >= 50
        assertFalse(sqlClient.createQuery(t).where(t.id().eq(1L))
                .where(Expression.value(fifty).lt(t.price())).select(t.id()).execute().isEmpty());
        // column on the left is correct
        assertTrue(sqlClient.createQuery(t).where(t.id().eq(1L))
                .where(t.price().lt(fifty)).select(t.id()).execute().isEmpty());
    }

    @Test
    public void updateWhereWithConstantOnTheLeftAcceptsEqualValues() throws Exception {
        // interceptors force a pre-query, where the predicate is evaluated through the same negation
        JSqlClient sqlClient = client("kb2", true);
        SimpleSaveResult<Book> wrong = sqlClient
                .saveCommand(Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("50.00"))))
                .setMode(SaveMode.UPDATE_ONLY)
                .setUpdateWhere(BookTable.class, (t, v) -> v.newNumber(BookProps.PRICE).gt(t.price()))
                .execute();
        assertTrue(wrong.isAccepted());                // should have been rejected: 50 > 50 is false
        SimpleSaveResult<Book> right = sqlClient
                .saveCommand(Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("50.00"))))
                .setMode(SaveMode.UPDATE_ONLY)
                .setUpdateWhere(BookTable.class, (t, v) -> t.price().lt(v.newNumber(BookProps.PRICE)))
                .execute();
        assertFalse(right.isAccepted());
    }

    @Test
    public void inIfIgnoresNullButNotEmpty() throws Exception {
        JSqlClient sqlClient = client("kb3", false);
        BookTable t = BookTable.$;
        assertEquals(6, sqlClient.createQuery(t).where(t.name().inIf((Collection<String>) null)).select(t.id()).execute().size());
        assertEquals(0, sqlClient.createQuery(t).where(t.name().inIf(Collections.emptyList())).select(t.id()).execute().size());
    }

    @Test
    public void lambdaExceptionTranslatorIsRejected() throws Exception {
        JSqlClient sqlClient = client("kb4", true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> sqlClient
                .saveCommand(Immutables.createBook(d -> d.setName("Learning GraphQL").setEdition(1).setPrice(BigDecimal.ONE).setTenant("a")))
                .setMode(SaveMode.INSERT_ONLY)
                .addExceptionTranslator((ExceptionTranslator<SaveException.NotUnique>) (e, args) -> new IllegalStateException("dup"))
                .execute());
        assertTrue(ex.getMessage().contains("Illegal exception translator type"), ex.getMessage());
    }

    @Test
    public void middleTableInsertFromSelectNeedsRootlessSource() throws Exception {
        JSqlClient sqlClient = client("kb5", false);
        AssociationTable<Book, BookTableEx, Author, AuthorTableEx> assoc =
                AssociationTable.of(BookTableEx.class, BookTableEx::authors);

        BookAuthorPairTable constants = sqlClient.createBaseQuery()
                .select(BookAuthorPairMapper.bookId(Expression.value(4L)).authorId(Expression.value(1L)))
                .asBaseTable();
        assertEquals(1, sqlClient.createInsert(assoc, constants)
                .set(assoc.<Long>sourceId(), constants.getBookId())
                .set(assoc.<Long>targetId(), constants.getAuthorId())
                .onConflictDoNothing()
                .execute());

        BookTable b = BookTable.$;
        BookAuthorPairTable fromEntity = sqlClient.createBaseQuery(b).where(b.id().eq(5L))
                .select(BookAuthorPairMapper.bookId(b.id()).authorId(Expression.value(1L)))
                .asBaseTable();
        assertThrows(UnsupportedOperationException.class, () -> sqlClient.createInsert(assoc, fromEntity)
                .set(assoc.<Long>sourceId(), fromEntity.getBookId())
                .set(assoc.<Long>targetId(), fromEntity.getAuthorId())
                .onConflictDoNothing()
                .execute());
    }
}
