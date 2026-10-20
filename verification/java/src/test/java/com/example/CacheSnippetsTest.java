package com.example;

import com.example.model.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import com.example.runtime.TenantFilter;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheAbandonedCallback;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineValueBinder;
import org.babyfish.jimmer.sql.cache.chain.ChainCacheBuilder;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.h2.jdbcx.JdbcDataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CacheSnippetsTest {

    private final List<String> sqls = new ArrayList<>();

    private JSqlClient client(String db, boolean withTenantFilter) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
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
        JSqlClient.Builder builder = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect())
                .setExecutor(new Executor() {
                    final Executor delegate = Executor.log();
                    @Override
                    public <R> R execute(@NotNull Args<R> args) {
                        sqls.add(args.sql);
                        return delegate.execute(args);
                    }
                    @Override
                    public BatchContext executeBatch(@NotNull Connection con, @NotNull String sql,
                            org.babyfish.jimmer.meta.ImmutableProp generatedIdProp,
                            @NotNull org.babyfish.jimmer.sql.runtime.ExecutionPurpose purpose,
                            @NotNull org.babyfish.jimmer.sql.runtime.JSqlClientImplementor sqlClient,
                            boolean constraintViolationTranslatable) {
                        sqls.add(sql);
                        return delegate.executeBatch(con, sql, generatedIdProp, purpose, sqlClient, constraintViolationTranslatable);
                    }
                })
                .setTriggerType(TriggerType.TRANSACTION_ONLY)
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor())
                .addCacheAbandonedCallback(CacheAbandonedCallback.log())
                .setCacheFactory(new CacheFactory() {
                    @Override
                    public Cache<?, ?> createObjectCache(@NotNull ImmutableType type) {
                        return new ChainCacheBuilder<Object, Object>()
                                .add(CaffeineValueBinder.forObject(type).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
                                .build();
                    }
                    @Override
                    public Cache<?, List<?>> createAssociatedIdListCache(@NotNull ImmutableProp prop) {
                        return new ChainCacheBuilder<Object, List<?>>()
                                .add(CaffeineValueBinder.<Object, List<?>>forProp(prop).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
                                .build();
                    }
                    @Override
                    public Cache<?, ?> createResolverCache(@NotNull ImmutableProp prop) {
                        return new ChainCacheBuilder<Object, Object>()
                                .add(CaffeineValueBinder.forProp(prop).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
                                .build();
                    }
                });
        if (withTenantFilter) {
            builder.addFilters(new TenantFilter());
        }
        return builder.build();
    }

    @Test
    public void objectAndAssociationCaches() throws Exception {
        JSqlClient sqlClient = client("c1", false);
        assertEquals("Learning GraphQL", sqlClient.findById(Book.class, 1L).name());
        int before = sqls.size();
        assertEquals("Learning GraphQL", sqlClient.findById(Book.class, 1L).name());
        assertEquals(before, sqls.size());                         // served from cache
        Map<Long, Book> map = sqlClient.findMapByIds(Book.class, List.of(1L, 2L, 999L));
        assertEquals(2, map.size());
        before = sqls.size();
        sqlClient.findMapByIds(Book.class, List.of(1L, 2L, 999L));  // 1, 2 cached; the miss 999 is queried again
        assertEquals(before + 1, sqls.size());

        // association + object caches through a fetcher; root query always hits SQL
        BookStoreTable store = BookStoreTable.$;
        // 0.12.4: with an association cache, the child fetcher must contain the orderedProps (name, edition)
        assertThrows(org.babyfish.jimmer.UnloadedException.class, () -> sqlClient.createQuery(store)
                .select(store.fetch(BookStoreFetcher.$.name().books(BookFetcher.$.name())))
                .execute());
        List<BookStore> stores = sqlClient.createQuery(store).orderBy(store.id())
                .select(store.fetch(BookStoreFetcher.$.name().books(BookFetcher.$.name().edition()).avgPrice()))
                .execute();
        assertEquals(4, stores.get(0).books().size());
        before = sqls.size();
        sqlClient.createQuery(store).orderBy(store.id())
                .select(store.fetch(BookStoreFetcher.$.name().books(BookFetcher.$.name().edition()).avgPrice()))
                .execute();
        assertEquals(before + 1, sqls.size());                     // only the root query

        // TRANSACTION_ONLY trigger invalidates object cache + resolver cache
        sqlClient.save(Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("100.00"))));
        assertEquals(0, new BigDecimal("100.00").compareTo(sqlClient.findById(Book.class, 1L).price()));
        BigDecimal avg = sqlClient.findById(BookStoreFetcher.$.avgPrice(), 1L).avgPrice();
        assertEquals(0, new BigDecimal("69.75").compareTo(avg), avg.toString());

        // moving a book evicts BookStore.books of both stores
        sqlClient.save(Immutables.createBook(d -> d.setId(1L).setStoreId(2L)));
        BookStoreFetcher withBooks = BookStoreFetcher.$.books(BookFetcher.$.name().edition());
        assertEquals(3, sqlClient.findById(withBooks, 1L).books().size());
        assertEquals(3, sqlClient.findById(withBooks, 2L).books().size());
        assertThrows(org.babyfish.jimmer.UnloadedException.class,
                () -> sqlClient.findById(BookStoreFetcher.$.books(), 1L));   // id-only children can't be sorted

        // manual eviction + bypass
        sqlClient.getCaches().getObjectCache(Book.class).delete(1L);
        sqlClient.getCaches().getPropertyCache(BookStoreProps.BOOKS).deleteAll(List.of(1L, 2L));
        before = sqls.size();
        sqlClient.caches(cfg -> cfg.disableAll()).findById(Book.class, 2L);
        sqlClient.caches(cfg -> cfg.disable(Book.class)).findById(Book.class, 2L);
        sqlClient.caches(cfg -> cfg.disable(BookStoreProps.BOOKS)).findById(withBooks, 1L);
        assertEquals(before + 3, sqls.size());   // 2 x Book by id + BookStore.books association (root store from cache)
    }

    @Test
    public void objectCacheIgnoresFiltersForFindById() throws Exception {
        JSqlClient sqlClient = client("c2", true);
        // book 2 belongs to tenant "b": a query hides it...
        BookTable book = BookTable.$;
        assertTrue(sqlClient.createQuery(book).where(book.id().eq(2L)).select(book).execute().isEmpty());
        // ...findById with an object cache depends on whether the object is already cached
        assertNull(sqlClient.findById(Book.class, 2L));                       // miss: loaded with the filter
        assertNotNull(sqlClient.filters(c -> c.disableAll()).findById(Book.class, 2L));   // caches book 2
        assertNotNull(sqlClient.findById(Book.class, 2L));                    // hit: returned despite the filter
    }
}
