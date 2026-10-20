package com.example;

import com.example.model.Book;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import com.example.runtime.TenantFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.jackson.v2.ImmutableModuleV2;
import org.babyfish.jimmer.sql.EnumType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.babyfish.jimmer.sql.meta.UserIdGenerator;
import org.babyfish.jimmer.sql.runtime.*;
import org.babyfish.jimmer.sql.transaction.Propagation;
import org.h2.jdbcx.JdbcDataSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class SetupSnippetsTest {

    static class MyIdGenerator implements UserIdGenerator<Long> {
        @Override
        public Long generate(Class<?> entityType) {
            return System.nanoTime();
        }
    }

    static ConnectionManager customConnectionManager(DataSource dataSource) {
        return new ConnectionManager() {
            @Override
            public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
                if (con != null) {
                    return block.apply(con);
                }
                try (Connection c = dataSource.getConnection()) {
                    return block.apply(c);
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
    }

    @Test
    public void builderCompiles() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:setup;DB_CLOSE_DELAY=-1");
        JSqlClient sqlClient = JSqlClient
                .newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .setSqlFormatter(SqlFormatter.PRETTY)
                .setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)
                .setDefaultEnumStrategy(EnumType.Strategy.NAME)
                .setDefaultBatchSize(128)
                .setDefaultListBatchSize(16)
                .setForeignKeyEnabledByDefault(true)
                .setDatabaseValidationMode(DatabaseValidationMode.NONE)
                .addFilters(new TenantFilter())
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor())
                .addScalarProvider(ScalarProvider.uuidByString())
                .setIdGenerator(Book.class, new MyIdGenerator())
                .setTriggerType(TriggerType.TRANSACTION_ONLY)
                .setInListPaddingEnabled(true)
                .setOffsetOptimizingThreshold(1000)
                .setDefaultReferenceFetchType(ReferenceFetchType.SELECT)
                .setIdOnlyTargetCheckingLevel(IdOnlyTargetCheckingLevel.NONE)
                .build();
        assertNotNull(sqlClient);

        JSqlClient another = JSqlClient.newBuilder()
                .setConnectionManager(customConnectionManager(dataSource))
                .setDialect(new H2Dialect())
                .build();
        assertThrows(IllegalStateException.class, () -> another.transaction(() -> 1)); // not a TxConnectionManager
        Object r = sqlClient.transaction(Propagation.REQUIRES_NEW, () -> null);
        assertNull(r);
    }

    @Test
    public void jackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new ImmutableModuleV2())
                .registerModule(new JavaTimeModule());
        Book book = Immutables.createBook(d -> d.setId(1L).setName("SQL").setPrice(new BigDecimal("1.5")));
        String json = mapper.writeValueAsString(book);
        assertEquals("{\"id\":1,\"name\":\"SQL\",\"price\":1.5}", json);
        Book back = mapper.readValue(json, Book.class);
        assertEquals("SQL", back.name());
        assertFalse(ImmutableObjects.isLoaded(back, "edition"));
        assertEquals(json, book.toString());
        Book parsed = ImmutableObjects.fromString(Book.class, json);
        assertEquals(1L, parsed.id());
    }
}
