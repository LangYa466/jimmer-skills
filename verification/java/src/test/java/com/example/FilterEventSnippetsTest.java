package com.example;

import com.example.model.*;
import com.example.model.common.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantFilter;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.babyfish.jimmer.jackson.codec.JsonReader;
import org.babyfish.jimmer.jackson.codec.Node;
import org.babyfish.jimmer.sql.DraftInterceptor;
import org.babyfish.jimmer.sql.DraftPreProcessor;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.event.AssociationEvent;
import org.babyfish.jimmer.sql.event.ChangedRef;
import org.babyfish.jimmer.sql.event.EntityEvent;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior;
import org.h2.jdbcx.JdbcDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FilterEventSnippetsTest {

    private JdbcDataSource dataSource(String name) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection();
             InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) stmt.execute(part);
                }
            }
        }
        return ds;
    }

    static class TenantPreProcessor implements DraftPreProcessor<TenantAwareDraft> {
        @Override
        public void beforeSave(@NotNull TenantAwareDraft draft) {
            if (!ImmutableObjects.isLoaded(draft, TenantAwareProps.TENANT)) {
                draft.setTenant("b");
            }
        }
        @Override public boolean ignoreIdOnly() { return true; }
    }

    static class AuditInterceptor implements DraftInterceptor<BaseEntity, BaseEntityDraft> {
        final List<Boolean> inserts = new ArrayList<>();
        @Override
        public void beforeSave(@NotNull BaseEntityDraft draft, @Nullable BaseEntity original) {
            inserts.add(original == null);
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            if (!ImmutableObjects.isLoaded(draft, BaseEntityProps.MODIFIED_TIME)) draft.setModifiedTime(now);
            if (original == null && !ImmutableObjects.isLoaded(draft, BaseEntityProps.CREATED_TIME)) draft.setCreatedTime(now);
            if (original != null) assertNotNull(original.createdTime());   // loaded through dependencies()
        }
        @Override
        public java.util.Collection<org.babyfish.jimmer.meta.TypedProp<BaseEntity, ?>> dependencies() {
            return List.of(BaseEntityProps.CREATED_TIME);
        }
        @Override public boolean ignoreIdOnly() { return true; }
    }

    static class KeyChangingInterceptor implements DraftInterceptor<Book, BookDraft> {
        @Override
        public void beforeSave(@NotNull BookDraft draft, @Nullable Book original) {
            draft.setName(draft.name() + "!");
        }
    }

    @Test
    public void filtersAndLogicalDeletion() throws Exception {
        TenantFilter.CURRENT_TENANT.set("a");
        JSqlClient sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe1")))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .addFilters(new TenantFilter())
                .addDraftInterceptors(new BaseEntityDraftInterceptor())
                .addDraftPreProcessor(new TenantPreProcessor())
                .build();
        BookTable book = BookTable.$;
        assertEquals(3, sqlClient.createQuery(book).select(book).execute().size());
        assertEquals(6, sqlClient.filters(cfg -> cfg.disableByTypes(TenantFilter.class)).createQuery(book).select(book).execute().size());
        assertEquals(6, sqlClient.filters(cfg -> cfg.disableAll()).createQuery(book).select(book).execute().size());
        // fetched associations are filtered too
        assertEquals(1, sqlClient.findById(AuthorFetcher.$.books(), 4L).books().size());

        // pre-processor sets tenant "b" for a new book
        long id = sqlClient.save(Immutables.createBook(d -> d.setName("Pre").setEdition(1).setPrice(BigDecimal.ONE)))
                .getModifiedEntity().id();
        assertEquals("b", sqlClient.filters(cfg -> cfg.disableAll()).findById(BookFetcher.$.tenant(), id).tenant());

        AuthorTable author = AuthorTable.$;
        sqlClient.deleteById(Author.class, 1L);
        assertEquals(3, sqlClient.createQuery(author).select(author).execute().size());
        assertEquals(3, sqlClient.filters(cfg -> cfg.disableAll()).createQuery(author).select(author).execute().size());
        assertEquals(4, sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED)).createQuery(author).select(author).execute().size());
        assertEquals(1, sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.REVERSED)).createQuery(author).select(author).execute().size());
        assertEquals(4, sqlClient.filters(cfg -> cfg.setBehavior(Author.class, LogicalDeletedBehavior.IGNORED)).createQuery(author).select(author).execute().size());

        // restore
        sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))
                .createUpdate(author).set(author.deleted(), false).where(author.id().eq(1L)).execute();
        assertEquals(4, sqlClient.createQuery(author).select(author).execute().size());
        sqlClient.deleteById(Author.class, 1L, DeleteMode.PHYSICAL);
        assertEquals(3, sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED)).createQuery(author).select(author).execute().size());
    }

    @Test
    public void interceptors() throws Exception {
        TenantFilter.CURRENT_TENANT.set("a");
        AuditInterceptor audit = new AuditInterceptor();
        JSqlClient sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe2")))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .addDraftInterceptors(audit)
                .addDraftPreProcessor(new TenantPreProcessor())
                .build();
        sqlClient.save(Immutables.createBookStore(d -> d.setName("NEW")));
        sqlClient.save(Immutables.createBookStore(d -> d.setName("NEW").setWebsite("w")));
        assertEquals(List.of(true, false), audit.inserts);

        // a root carrying only its id is treated as a reference: interceptor skipped (ignoreIdOnly)
        audit.inserts.clear();
        sqlClient.save(Immutables.createBook(d -> d.setId(1L)));
        assertTrue(audit.inserts.isEmpty());
        // id-only association targets are references and never reach the interceptor
        sqlClient.save(Immutables.createBook(d -> d.setId(1L).setAuthorIds(List.of(1L))));
        assertEquals(List.of(false), audit.inserts);            // only the Book root itself

        JSqlClient bad = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe3")))
                .setDialect(new H2Dialect())
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new KeyChangingInterceptor())
                .addDraftPreProcessor(new TenantPreProcessor())
                .setExecutor(Executor.log())
                .build();
        // changing a key in beforeSave is NOT rejected: the key-matched UPDATE ignores it, the result shows it
        Book returned = bad.save(Immutables.createBook(d -> d.setName("Learning GraphQL").setEdition(1).setPrice(BigDecimal.ONE)))
                .getModifiedEntity();
        assertEquals("Learning GraphQL!", returned.name());
        assertEquals("Learning GraphQL", bad.findById(BookFetcher.$.name(), 1L).name());
    }

    @Test
    public void transactionTriggers() throws Exception {
        TenantFilter.CURRENT_TENANT.set("a");
        JSqlClient sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe4")))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .setTriggerType(TriggerType.TRANSACTION_ONLY)
                .addDraftInterceptors(new BaseEntityDraftInterceptor())
                .addDraftPreProcessor(new TenantPreProcessor())
                .build();
        List<String> log = new ArrayList<>();
        sqlClient.getTriggers().addEntityListener(Book.class, e -> {
            if (e.isChanged(BookProps.PRICE)) {
                ChangedRef<BigDecimal> price = e.getChangedRef(BookProps.PRICE);
                log.add(e.getType() + " price " + price.getOldValue() + "->" + price.getNewValue());
            }
            assertNotNull(e.getConnection());
        });
        List<AssociationEvent> assocEvents = new ArrayList<>();
        sqlClient.getTriggers().addAssociationListener(BookProps.AUTHORS, assocEvents::add);
        List<AssociationEvent> storeBooks = new ArrayList<>();
        sqlClient.getTriggers().addAssociationListener(BookStoreProps.BOOKS, storeBooks::add);
        List<EntityEvent<?>> all = new ArrayList<>();
        sqlClient.getTriggers().addEntityListener(all::add);

        sqlClient.save(Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("60.00"))));
        assertEquals(List.of("UPDATE price 50.00->60.00"), log);

        sqlClient.save(Immutables.createBook(d -> d.setId(1L).setAuthorIds(List.of(1L, 3L))));
        assertTrue(assocEvents.stream().anyMatch(e -> Long.valueOf(3L).equals(e.getAttachedTargetId())));
        assertTrue(assocEvents.stream().anyMatch(e -> Long.valueOf(2L).equals(e.getDetachedTargetId())));

        sqlClient.save(Immutables.createBook(d -> d.setId(1L).setStoreId(2L)));
        assertFalse(storeBooks.isEmpty());                      // FK change => inverse association event
        sqlClient.deleteById(Author.class, 4L);
        assertTrue(all.stream().anyMatch(e -> e.getType() == EntityEvent.Type.LOGICAL_DELETED));
    }

    @Test
    public void binLog() throws Exception {
        JSqlClient sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe5")))
                .setDialect(new H2Dialect())
                .build();
        List<String> log = new ArrayList<>();
        sqlClient.getTriggers().addEntityListener(BookStore.class, e -> log.add(e.getType() + ":" + e.getNewEntity()));
        sqlClient.getTriggers().addAssociationListener(BookProps.AUTHORS, e -> log.add("assoc:" + e.getSourceId() + "+" + e.getAttachedTargetId()));

        JsonReader<Node> reader = JsonCodec.jsonCodec().treeReader();
        Node msg = reader.read("{\"table\":\"book_store\",\"type\":\"update\",\"old\":{\"id\":1,\"name\":\"A\"},\"data\":{\"id\":1,\"name\":\"B\"}}");
        String table = msg.get("table").castTo(String.class);
        switch (msg.get("type").castTo(String.class)) {
            case "insert" -> sqlClient.getBinLog().accept(table, null, msg.get("data"));
            case "update" -> sqlClient.getBinLog().accept(table, msg.get("old"), msg.get("data"));
            case "delete" -> sqlClient.getBinLog().accept(table, msg.get("data"), null);
            default -> fail();
        }
        sqlClient.getBinLog().accept("BOOK_AUTHOR_MAPPING", null, reader.read("{\"BOOK_ID\":1,\"AUTHOR_ID\":4}"));
        assertEquals(2, log.size(), log.toString());
        assertTrue(log.get(0).startsWith("UPDATE:"));
        assertEquals("assoc:1+4", log.get(1));
    }

    @Test
    public void binLogDecimal() throws Exception {
        JsonReader<Node> reader = JsonCodec.jsonCodec().treeReader();
        JSqlClient broken = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe6")))
                .setDialect(new H2Dialect())
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                broken.getBinLog().accept("book", null, reader.read("{\"id\":1,\"price\":70.50}")));

        JSqlClient sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource("fe7")))
                .setDialect(new H2Dialect())
                .setBinLogPropReader(BigDecimal.class, (prop, node) -> new BigDecimal(node.castTo(String.class)))
                .build();
        List<BigDecimal> prices = new ArrayList<>();
        sqlClient.getTriggers().addEntityListener(Book.class, e -> prices.add(e.getNewEntity().price()));
        sqlClient.getBinLog().accept("book", null, reader.read("{\"id\":1,\"price\":70.50}"));
        assertEquals(0, new BigDecimal("70.5").compareTo(prices.get(0)));
    }
}
