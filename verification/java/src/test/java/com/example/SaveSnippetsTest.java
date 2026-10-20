package com.example;

import com.example.dto.BookInput;
import com.example.dto.BookView;
import com.example.model.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import com.example.runtime.TenantFilter;
import org.babyfish.jimmer.sql.DissociateAction;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.TargetTransferMode;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.Predicate;
import org.babyfish.jimmer.sql.ast.TypeMatchMode;
import org.babyfish.jimmer.sql.ast.mutation.*;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.exception.SaveException;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.runtime.IdOnlyTargetCheckingLevel;
import org.h2.jdbcx.JdbcDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SaveSnippetsTest {

    private JSqlClient sqlClient;

    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:js;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection();
             InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) stmt.execute(part);
                }
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

    private Book newBook(String name, int edition) {
        return Immutables.createBook(d -> d.setName(name).setEdition(edition).setPrice(new BigDecimal("10")));
    }

    @Test
    public void entryPointsAndShapes() {
        sqlClient.saveCommand(Immutables.createBook(d -> d.setId(1L).setStore(null)))
                .setMode(SaveMode.UPDATE_ONLY).execute();
        Book b1 = sqlClient.findById(BookFetcher.$.name().storeId(), 1L);
        assertEquals("Learning GraphQL", b1.name());
        assertNull(b1.storeId());

        Book book = newBook("New", 1);
        assertTrue(sqlClient.save(book, SaveMode.INSERT_ONLY, AssociatedSaveMode.APPEND).isAccepted());
        sqlClient.save(book, SaveMode.UPDATE_ONLY, AssociatedSaveMode.UPDATE);
        SimpleSaveResult<Book> ignored = sqlClient.save(book, SaveMode.INSERT_IF_ABSENT, AssociatedSaveMode.APPEND_IF_ABSENT);
        assertFalse(ignored.isAccepted());
        sqlClient.save(book, SaveMode.UPSERT, AssociatedSaveMode.MERGE);
        sqlClient.saveCommand(book).setMode(SaveMode.UPDATE_ONLY).execute();

        BookStore store = Immutables.createBookStore(d -> d.setName("MANNING").addIntoBooks(b -> b.setName("Extra").setEdition(1).setPrice(BigDecimal.ONE)));
        sqlClient.saveCommand(store)
                .setAssociatedModeAll(AssociatedSaveMode.MERGE)
                .setAssociatedMode(BookStoreProps.BOOKS, AssociatedSaveMode.APPEND)
                .execute();
        assertEquals(3, sqlClient.findById(BookStoreFetcher.$.books(), 2L).books().size());

        Book withAuthorKey = Immutables.createBook(d -> d
                .setName("Book").setEdition(1).setPrice(BigDecimal.TEN)
                .addIntoAuthors(a -> a.setFirstName("Alex").setLastName("Banks")));
        SimpleSaveResult<Book> r = sqlClient.saveCommand(withAuthorKey)
                .setKeyOnlyAsReference(BookProps.AUTHORS)
                .execute();
        assertEquals(1, r.getAffectedRowCount(BookProps.AUTHORS));
        assertFalse(r.getAffectedRowCountMap().isEmpty());

        sqlClient.saveCommand(newBook("Book", 1)).setKeyProps(BookProps.NAME, BookProps.EDITION).execute();
        Book matched = sqlClient.saveCommand(Immutables.createBook(d -> d.setId(999L).setName("Book").setEdition(1).setPrice(BigDecimal.ONE)))
                .matchByKey()
                .execute()
                .getModifiedEntity();
        assertNotEquals(999L, matched.id());

        assertThrows(SaveException.IllegalTargetId.class, () ->
                sqlClient.saveCommand(Immutables.createBook(d -> d.setId(1L).addIntoAuthors(a -> a.setId(12345L))))
                        .setAutoIdOnlyTargetChecking(BookProps.AUTHORS)
                        .execute());
        assertNotNull(IdOnlyTargetCheckingLevel.FAKE);
    }

    @Test
    public void dissociationAndTransfer() {
        // book 5 belongs to MANNING(2); saving O'REILLY(1) with it steals it
        BookStore steal = Immutables.createBookStore(d -> d.setId(1L).addIntoBooks(b -> b.setId(5L)));
        assertThrows(SaveException.TargetIsNotTransferable.class, () ->
                sqlClient.saveCommand(steal).setAssociatedModeAll(AssociatedSaveMode.MERGE).execute());
        sqlClient.saveCommand(steal)
                .setAssociatedModeAll(AssociatedSaveMode.MERGE)
                .setTargetTransferMode(BookStoreProps.BOOKS, TargetTransferMode.ALLOWED)
                .execute();
        assertEquals(1L, sqlClient.findById(BookFetcher.$.storeId(), 5L).storeId());
        sqlClient.saveCommand(steal).setTargetTransferModeAll(TargetTransferMode.ALLOWED).setAssociatedModeAll(AssociatedSaveMode.MERGE).execute();

        // REPLACE + DELETE: MANNING keeps only book 6, others are deleted
        BookStore manning = Immutables.createBookStore(d -> d.setId(2L).addIntoBooks(b -> b.setId(6L)));
        sqlClient.saveCommand(manning)
                .setDissociateAction(BookProps.STORE, DissociateAction.DELETE)
                .setDissociationLogicalDeleteEnabled(true)
                .execute();
        assertEquals(1, sqlClient.findById(BookStoreFetcher.$.books(), 2L).books().size());

        // REPLACE with default SET_NULL (declared on Book.store)
        sqlClient.save(Immutables.createBookStore(d -> d.setId(1L).addIntoBooks(b -> b.setId(1L))));
        assertNull(sqlClient.findById(BookFetcher.$.storeId(), 2L).storeId());
    }

    @Test
    public void resultsAndReturning() {
        BatchSaveResult<Book> br = sqlClient.saveEntities(List.of(newBook("A", 1), newBook("B", 1)));
        for (BatchSaveResult.Item<Book> item : br.getItems()) {
            assertTrue(item.isAccepted());
            assertTrue(item.getModifiedEntity().id() > 0);
        }
        assertEquals(2, br.getAffectedRowCount(Book.class));
        SimpleSaveResult<Book> r = sqlClient.save(newBook("C", 1));
        assertTrue(r.isModified());
        assertNotNull(r.getOriginalEntity());
        assertEquals(1, r.getTotalAffectedRowCount());

        Book book = Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("99")));
        Book saved = sqlClient.saveCommand(book)
                .execute(BookFetcher.$.allScalarFields().store(BookStoreFetcher.$.name()))
                .getModifiedEntity();
        assertEquals("O'REILLY", saved.store().name());

        BookView view = sqlClient.saveCommand(book).execute(BookView.class).getModifiedView();
        assertEquals(2, view.getAuthors().size());
        List<BatchSaveResult.View.ViewItem<Book, BookView>> items =
                sqlClient.saveEntitiesCommand(List.of(book)).execute(BookView.class).getViewItems();
        assertEquals("Learning GraphQL", items.get(0).getModifiedView().getName());

        sqlClient.saveCommand(book).setSaveResultReadsAllProperties().setSaveReturningEnabled(false).execute();
    }

    @Test
    public void updateControl() {
        Book book = Immutables.createBook(d -> d.setName("Learning GraphQL").setEdition(1).setPrice(new BigDecimal("1.00")).setStoreId(2L));
        sqlClient.saveCommand(book).setUpsertMask(BookProps.PRICE).execute();
        Book after = sqlClient.findById(BookFetcher.$.price().storeId(), 1L);
        assertEquals(0, new BigDecimal("1.00").compareTo(after.price()));
        assertEquals(1L, after.storeId());              // STORE_ID not in mask

        UpsertMask<Book> mask = UpsertMask.of(Book.class)
                .addInsertableProp(BookProps.NAME).addInsertableProp(BookProps.EDITION)
                .addInsertableProp(BookProps.PRICE)
                .addUpdatableProp(BookProps.PRICE);
        sqlClient.saveCommand(book).setUpsertMask(mask).execute();

        assertTrue(sqlClient.saveCommand(book).forbidUpdate().execute().isAccepted());

        SimpleSaveResult<Book> r = sqlClient.saveCommand(book)
                .setUpdateWhere(BookTable.class,
                        (table, values) -> table.price().lt(values.newNumber(BookProps.PRICE)))
                .execute();
        assertFalse(r.isAccepted());                      // 1.00 is not > 1.00

        sqlClient.saveCommand(Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("5"))))
                .setMode(SaveMode.UPDATE_ONLY)
                .set(BookTable.class, BookProps.PRICE,
                        (target, values) -> target.price().plus(values.newNumber(BookProps.PRICE)))
                .execute();
        assertEquals(0, new BigDecimal("6.00").compareTo(sqlClient.findById(BookFetcher.$.price(), 1L).price()));

        BookStore store = Immutables.createBookStore(d -> d.setId(1L).setName("O'REILLY").setVersion(7));
        SimpleSaveResult<BookStore> vr = sqlClient.saveCommand(store)
                .setVersionMode(VersionMode.ASSIGNMENT)
                .setUpdateWhere(BookStoreTable.class,
                        (t, v) -> t.version().lt(v.newNumber(BookStoreProps.VERSION)))
                .execute();
        assertTrue(vr.isAccepted());
        assertEquals(7, sqlClient.findById(BookStoreFetcher.$.version(), 1L).version());
    }

    @Test
    public void locking() {
        BookStore stale = Immutables.createBookStore(d -> d.setId(1L).setWebsite("x").setVersion(42));
        assertThrows(SaveException.OptimisticLockError.class, () -> sqlClient.save(stale));

        List<BookStore> stores = Arrays.asList(
                Immutables.createBookStore(d -> d.setId(1L).setWebsite("https://www.oreilly.com")),
                Immutables.createBookStore(d -> d.setId(2L).setWebsite("https://www.manning.com")));
        sqlClient.saveEntitiesCommand(stores)
                .setMode(SaveMode.UPDATE_ONLY)
                .setOptimisticLock(BookStoreTable.class, (table, values) ->
                        Predicate.sql("coalesce(length(%e), 0) <= length(%e)", new Expression<?>[] {
                                table.website(), values.newString(BookStoreProps.WEBSITE) }))
                .execute();

        BookStore store = Immutables.createBookStore(d -> d.setId(1L).setName("O'REILLY").setWebsite("w"));
        sqlClient.saveCommand(store)
                .setOptimisticLock(BookStoreTable.class,
                        UnloadedVersionBehavior.INCREASE,
                        (table, values) -> table.name().eq(values.newString(BookStoreProps.NAME)))
                .execute();
        assertEquals(1, sqlClient.findById(BookStoreFetcher.$.version(), 1L).version());

        sqlClient.transaction(() -> {
            sqlClient.saveEntitiesCommand(List.of(newBook("L1", 1))).setPessimisticLock(Book.class).execute();
            sqlClient.saveCommand(store).setPessimisticLockAll().execute();
            return null;
        });
    }

    @Test
    public void inputsAndErrors() {
        BookInput in1 = new BookInput(newBook("I1", 1));
        BookInput in2 = new BookInput(newBook("I2", 1));
        assertEquals(2, sqlClient.saveInputs(List.of(in1, in2)).getItems().size());
        sqlClient.save(in1);
        sqlClient.saveCommand(in1).setMode(SaveMode.UPSERT).execute();
        Book entity = in1.toEntity();
        assertEquals("I1", entity.name());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                sqlClient.saveCommand(newBook("Learning GraphQL", 1))
                        .setMode(SaveMode.INSERT_ONLY)
                        .addExceptionTranslator(new ExceptionTranslator<SaveException.NotUnique>() {
                            @Override
                            public @Nullable Exception translate(@NotNull SaveException.NotUnique e, @Nullable Args args) {
                                if (e.isMatched(BookProps.NAME, BookProps.EDITION)) {
                                    return new IllegalArgumentException("Book " + e.getValue(BookProps.NAME) +
                                            " edition " + e.getValue(BookProps.EDITION) + " already exists");
                                }
                                return null;
                            }
                        })
                        .execute());
        assertEquals("Book Learning GraphQL edition 1 already exists", ex.getMessage());

        SaveException.NeitherIdNorKey wild = assertThrows(SaveException.NeitherIdNorKey.class, () ->
                sqlClient.save(Immutables.createBook(d -> d.setPrice(BigDecimal.ONE))));
        assertNotNull(wild.getPath());
        assertNotNull(wild.getSaveErrorCode());

        sqlClient.saveCommand(newBook("Opt", 1))
                .setTypeMatchMode(TypeMatchMode.AUTO)
                .setTypeChangeAllowed()
                .setDumbBatchAcceptable()
                .setMaxCommandJoinCount(2)
                .setTransactionRequired(false)
                .setConstraintViolationTranslatable(true)
                .setDeleteMode(DeleteMode.AUTO)
                .setIdOnlyAsReferenceAll(true)
                .setIdOnlyAsReference(BookProps.AUTHORS, true)
                .setKeyOnlyAsReferenceAll()
                .setAutoIdOnlyTargetCheckingAll()
                .execute();
    }
}
