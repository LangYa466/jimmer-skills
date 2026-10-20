package com.example;

import com.example.dto.BookUpdateInput;
import com.example.dto.adv.*;
import com.example.dto.client.ClientView;
import com.example.mapping.ClientTable;
import com.example.model.*;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
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

import static org.junit.jupiter.api.Assertions.*;

public class DtoSnippetsTest {

    private JSqlClient sqlClient;

    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jd;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection(); InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) if (!part.isBlank()) stmt.execute(part);
                stmt.execute("create table client(id bigint primary key, client_type varchar(10) not null, name varchar(20) not null, tax_code varchar(20), first_name varchar(20), last_name varchar(20))");
                stmt.execute("insert into client values(1, 'ORG', 'Acme', 'T1', null, null), (2, 'Person', 'Bob', null, 'Bob', 'Lee')");
            }
        }
        sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .build();
    }

    @Test
    public void advancedDtos() {
        BookRefView ref = sqlClient.findById(BookRefView.class, 1L);
        assertEquals(1L, ref.getStoreId());

        BookRenamedView renamed = sqlClient.findById(BookRenamedView.class, 1L);
        assertEquals("Learning GraphQL", renamed.getTitle());
        assertEquals(1, renamed.getBookEdition());

        BookFilteredView filtered = sqlClient.findById(BookFilteredView.class, 1L);
        assertEquals(2, filtered.getAuthors().size()); // Eve and Alex both contain "e"

        BookFilterClassView filtered2 = sqlClient.findById(BookFilterClassView.class, 1L);
        assertEquals(2, filtered2.getAuthors().size());

        AuthorGenderView gender = sqlClient.findById(AuthorGenderView.class, 1L);
        assertEquals(101, gender.getGender());

        TreeDepthView tree = sqlClient.findById(TreeDepthView.class, 1L);
        assertNotNull(tree.getChildNodes());
        TreeStrategyView tree2 = sqlClient.findById(TreeStrategyView.class, 1L);
        assertNotNull(tree2);

        ClientTable c = ClientTable.$;
        List<ClientView> clients = sqlClient.createQuery(c).orderBy(c.id()).select(c.fetch(ClientView.class)).execute();
        assertInstanceOf(ClientView.Organization.class, clients.get(0));
        assertEquals("T1", ((ClientView.Organization) clients.get(0)).getTaxCode());
        assertInstanceOf(ClientView.Person.class, clients.get(1));

        BookAdvancedSpecification spec = new BookAdvancedSpecification();
        spec.setHasStore(true);
        spec.setEditions(List.of(1, 2));
        spec.setName("Learning");
        assertEquals(2, sqlClient.createQuery(BookTable.$).where(spec).select(BookTable.$).execute().size());

        UpdateBookInput update = new UpdateBookInput();
        update.setId(1L);
        update.setPrice(new BigDecimal("9.9"));
        Book entity = update.toEntity();
        assertTrue(ImmutableObjects.isLoaded(entity, BookProps.PRICE));
        assertFalse(ImmutableObjects.isLoaded(entity, BookProps.NAME));   // null + nullable(?) => static mode => unloaded? check below
        System.out.println("UpdateBookInput -> " + entity);

        BookUpdateInput dynamicInput = BookUpdateInput.class.cast(
                org.babyfish.jimmer.ImmutableObjects.fromString(BookUpdateInput.class, "{\"id\":1,\"name\":\"N\",\"price\":1,\"storeId\":null}"));
        System.out.println("dynamic -> " + dynamicInput.toEntity());
    }
}
