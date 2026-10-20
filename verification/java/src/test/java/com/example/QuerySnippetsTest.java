package com.example;

import com.example.mapping.*;
import com.example.model.*;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.NumericExpression;
import org.babyfish.jimmer.sql.ast.Predicate;
import org.babyfish.jimmer.sql.ast.query.*;
import org.babyfish.jimmer.sql.ast.table.base.BaseTable2;
import org.babyfish.jimmer.sql.ast.table.WeakJoin;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.ast.TypeMatchMode;
import java.util.Collection;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class QuerySnippetsTest {

    private JSqlClient sqlClient;


    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jq;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        try (Connection con = ds.getConnection();
             InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = con.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) stmt.execute(part);
                }
                stmt.execute("create table client(id bigint primary key, client_type varchar(10) not null, name varchar(20) not null, tax_code varchar(20), first_name varchar(20), last_name varchar(20))");
                stmt.execute("insert into client values(1, 'ORG', 'Acme', 'T1', null, null), (2, 'Person', 'Bob', null, 'Bob', 'Lee')");
            }
        }
        sqlClient = JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(ds))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor())
                .build();
    }

    @Test
    public void predicatesAndExpressions() {
        BookTable t = BookTable.$;
        List<String> names = sqlClient.createQuery(t)
                .where(t.name().notIn(List.of("X", "Y")))
                .where(t.name().inIf((Collection<String>) null))
                .where(t.storeId().isNotNull())
                .where(Expression.tuple(t.name(), t.edition()).in(List.of(new Tuple2<>("Learning GraphQL", 1), new Tuple2<>("GraphQL in Action", 2))))
                .where(Predicate.or(Predicate.not(t.name().like("Ab")), t.price().ge(BigDecimal.ONE)))
                .orderBy(t.edition().desc(), t.price().desc().nullsLast())
                .select(t.name())
                .execute();
        assertEquals(2, names.size());

        List<Tuple2<Long, BigDecimal>> grouped = sqlClient.createQuery(t)
                .groupBy(t.storeId())
                .having(t.price().avgAsDecimal().gt(new BigDecimal(50)))
                .select(t.storeId(), t.price().avgAsDecimal())
                .execute();
        assertFalse(grouped.isEmpty());

        List<Object> misc = sqlClient.createQuery(t)
                .where(t.id().eq(1L))
                .select(
                        t.price().plus(BigDecimal.ONE).minus(BigDecimal.ONE).times(BigDecimal.TEN).div(BigDecimal.TEN),
                        t.name().length(),
                        t.name().lower(),
                        t.name().upper(),
                        t.store().website().coalesceBuilder().or(t.store().name()).or("N/A").build(),
                        Expression.string().caseBuilder(t.name()).when("SQL in Action", "Matched").otherwise("Other"),
                        Expression.comparable().value(LocalDateTime.now()),
                        Expression.nullValue(Boolean.class),
                        Expression.numeric().sql(Integer.class, "rank() over(partition by %e order by %e desc)",
                                new Expression[] { t.storeId(), t.price() })
                )
                .map(tuple -> (Object) tuple);
        assertEquals(1, misc.size());

        assertEquals(1, sqlClient.createQuery(t).where(t.id().eq(1L)).select(Expression.string().sql("upper(%e)", t.name()), t.name().trim()).execute().size());
        AuthorTable author = AuthorTable.$;
        assertEquals(1, sqlClient.createQuery(author)
                .where(Predicate.sql("regexp_like(%e, %v)", it -> it.expression(author.firstName()).value("^Ev")))
                .select(author)
                .execute()
                .size());

        AuthorTableEx a = AuthorTableEx.$;
        BookStoreTable s = BookStoreTable.$;
        TypedSubQuery<BigDecimal> avg = sqlClient.createSubQuery(t)
                .where(t.store().eq(s))
                .select(t.price().avgAsDecimal().coalesce(BigDecimal.ZERO));
        List<Tuple2<BookStore, BigDecimal>> rows = sqlClient.createQuery(s).orderBy(avg.desc()).select(s, avg).execute();
        assertEquals(2, rows.size());
        List<Book> any = sqlClient.createQuery(t)
                .where(t.id().eq(sqlClient.createSubQuery(a).where(a.firstName().in(List.of("Alex"))).select(a.books().id()).any()))
                .select(t)
                .execute();
        assertFalse(any.isEmpty());
        long cnt = sqlClient.createQuery(s)
                .select(sqlClient.createSubQuery(t).where(t.store().eq(s)).selectCount())
                .execute().size();
        assertEquals(2, cnt);

        TypedRootQuery<Long> q1 = sqlClient.createQuery(t).where(t.edition().eq(1)).select(t.id());
        TypedRootQuery<Long> q2 = sqlClient.createQuery(t).where(t.edition().eq(2)).select(t.id());
        assertFalse(q1.union(q2).execute().isEmpty());
        assertFalse(TypedRootQuery.unionAll(q1, q2).execute().isEmpty());

        List<Long> locked = sqlClient.createQuery(t).where(t.id().eq(1L)).select(t.id())
                .forUpdate(LockMode.UPDATE, LockWait.DEFAULT).hint(null).execute();
        assertEquals(1, locked.size());

        List<Long> weak = sqlClient.createQuery(t)
                .where(t.asTableEx().weakJoin(BookAuthorJoin.class).firstName().eq("Alex"))
                .select(t.id()).distinct().execute();
        assertTrue(weak.isEmpty());

        assertTrue(sqlClient.createQuery(t).where(t.id().eq(1L)).select(t).exists());
        assertNotNull(sqlClient.createQuery(t).orderBy(t.id()).select(t).fetchFirst());
    }

    @Test
    public void baseQueries() {
        BookTable book = BookTable.$;
        AuthorTableEx author = AuthorTableEx.$;
        BaseTable2<BookTable, NumericExpression<Integer>> ranked = sqlClient
                .createBaseQuery(book)
                .addSelect(book)
                .addSelect(Expression.numeric().sql(Integer.class, "dense_rank() over(order by %e desc)",
                        sqlClient.createSubQuery(author).where(author.books().id().eq(book.id())).selectCount()))
                .asBaseTable();
        List<Book> top = sqlClient.createQuery(ranked)
                .where(ranked.get_2().eq(1))
                .select(ranked.get_1().fetch(BookFetcher.$.name()))
                .execute();
        assertFalse(top.isEmpty());

        BookStoreTable manning = sqlClient.createBaseQuery(BookStoreTable.$)
                .where(BookStoreTable.$.name().eq("MANNING"))
                .select(BookStoreTable.$)
                .asBaseTable();
        List<String> storeNames = sqlClient.createQuery(manning).select(manning.name()).execute();
        assertEquals(List.of("MANNING"), storeNames);

        StoreStatisticsTable stats = sqlClient.createBaseQuery(book)
                .where(book.storeId().isNotNull())
                .groupBy(book.storeId())
                .select(StoreStatisticsMapper.storeId(book.storeId()).bookCount(Expression.rowCount()).avgPrice(book.price().avgAsDecimal()))
                .asCteBaseTable();
        List<Long> storeIds = sqlClient.createQuery(stats).where(stats.getBookCount().gt(2L)).select(stats.getStoreId()).execute();
        assertEquals(1, storeIds.size());

        TreeNodeTable node = TreeNodeTable.$;
        BaseTable2<TreeNodeTable, NumericExpression<Integer>> tree = TypedBaseQuery.unionAllRecursively(
                sqlClient.createBaseQuery(node)
                        .where(node.parentId().isNull())
                        .addSelect(node)
                        .addSelect(Expression.constant(1)),
                recursiveRef -> {
                    MutableRecursiveBaseQuery<BaseTable2<TreeNodeTable, NumericExpression<Integer>>> q =
                            sqlClient.createBaseQuery(node, recursiveRef, (t2, r) -> t2.parentId().eq(r.get_1().id()));
                    return q.addSelect(node).addSelect(q.recursive().get_2().plus(Expression.constant(1)));
                }
        ).asCteBaseTable();
        List<Tuple2<TreeNode, Integer>> levels = sqlClient.createQuery(tree)
                .orderBy(tree.get_2(), tree.get_1().name())
                .select(tree.get_1().fetch(TreeNodeFetcher.$.name()), tree.get_2())
                .execute();
        assertEquals(9, levels.size());
    }

    @Test
    public void inheritance() {
        ClientTable c = ClientTable.$;
        List<Client> clients = sqlClient.createQuery(c).orderBy(c.id()).select(c).execute();
        assertInstanceOf(Organization.class, clients.get(0));
        assertInstanceOf(Person.class, clients.get(1));
        List<Tuple2<Long, String>> orgs = sqlClient.createQuery(c)
                .where(c.instanceOf(Organization.class))
                .select(c.id(), c.treatAs(OrganizationTable.class).taxCode())
                .execute();
        assertEquals(1, orgs.size());
        assertThrows(Exception.class, () -> sqlClient.createQuery(c).typeMatchMode(TypeMatchMode.EXACT).select(c).execute()); // Client is abstract
        assertEquals(1, sqlClient.createQuery(OrganizationTable.$).typeMatchMode(TypeMatchMode.EXACT).select(OrganizationTable.$).execute().size());
        List<Client> fetched = sqlClient.createQuery(c)
                .select(c.fetch(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode())))
                .execute();
        assertEquals("T1", ((Organization) fetched.get(0)).taxCode());
    }
}
