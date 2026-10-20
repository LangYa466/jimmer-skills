package com.example;

import com.example.dto.*;
import com.example.dto.catalog.BookCard;
import com.example.model.*;
import com.example.model.common.BaseEntityProps;
import com.example.runtime.BaseEntityDraftInterceptor;
import com.example.runtime.TenantDraftInterceptor;
import com.example.runtime.TenantFilter;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.Page;
import org.babyfish.jimmer.Slice;
import org.babyfish.jimmer.sql.DissociateAction;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.Predicate;
import org.babyfish.jimmer.sql.ast.mutation.*;
import org.babyfish.jimmer.sql.ast.query.Order;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.ast.tuple.Tuple3;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.exception.SaveException;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.ast.LikeMode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JimmerJavaTest implements Fetchers {

    private JSqlClient sqlClient;

    @BeforeEach
    public void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jt;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true");
        DataSource dataSource = ds;
        try (Connection con = dataSource.getConnection();
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
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
                .setDialect(new H2Dialect())
                .setExecutor(Executor.log())
                .addDraftInterceptors(new BaseEntityDraftInterceptor(), new TenantDraftInterceptor())
                .addFilters(new TenantFilter())
                .build();
        TenantFilter.CURRENT_TENANT.set("a");
    }

    // ---------------- Draft / immutable object ----------------

    @Test
    public void testDraft() {
        Book book = Immutables.createBook(draft -> {
            draft.setName("SQL in Action");
            draft.setEdition(1);
            draft.setPrice(new BigDecimal("49.9"));
            draft.applyStore(store -> store.setId(1L));
            draft.addIntoAuthors(author -> author.setId(1L));
            draft.addIntoAuthors(author -> author.setId(2L));
        });
        assertFalse(ImmutableObjects.isLoaded(book, BookProps.ID));
        assertTrue(ImmutableObjects.isLoaded(book, BookProps.STORE));
        assertEquals(1L, book.store().id());

        Book book2 = BookDraft.$.produce(book, draft -> {
            draft.setStoreId(2L); // @IdView setter changes `store` too
            draft.setPrice(new BigDecimal("59.9"));
        });
        assertEquals(2L, book2.store().id());
        assertEquals(new BigDecimal("49.9"), book.price()); // original unchanged
        System.out.println(book2);

        Book idOnly = ImmutableObjects.makeIdOnly(Book.class, 10L);
        assertTrue(ImmutableObjects.isIdOnly(idOnly));

        TreeNode tree = Immutables.createTreeNode(draft -> {
            draft.setName("Root");
            draft.addIntoChildNodes(child -> child.setName("Child-1"));
            draft.addIntoChildNodes(child -> child.setName("Child-2"));
        });
        TreeNode modified = Immutables.createTreeNode(tree, draft -> {
            draft.childNodes(false).get(0).setName("Child-1*");
        });
        assertEquals("Child-1*", modified.childNodes().get(0).name());
    }

    // ---------------- Query DSL ----------------

    @Test
    public void testQueryDsl() {
        BookTable table = BookTable.$;
        String name = "graphql";
        BigDecimal minPrice = null;
        List<Book> books = sqlClient
                .createQuery(table)
                .where(table.name().ilikeIf(name))
                .where(table.price().betweenIf(minPrice, new BigDecimal(100)))
                .where(table.store().name().eqIf("O'REILLY"))
                .where(
                        table.authors(author ->
                                Predicate.or(
                                        author.firstName().ilikeIf("e"),
                                        author.lastName().ilikeIf("e")
                                )
                        )
                )
                .orderBy(table.name().asc(), table.edition().desc())
                .select(table)
                .execute();
        // tenant filter keeps only tenant `a`
        assertEquals(2, books.size());

        List<Book> sorted = sqlClient
                .createQuery(table)
                .orderBy(Order.makeOrders(table, "store.name asc, name asc, edition desc"))
                .select(table)
                .execute();
        assertFalse(sorted.isEmpty());

        Page<Book> page = sqlClient
                .createQuery(table)
                .orderBy(table.id())
                .select(table)
                .fetchPage(0, 2);
        assertEquals(3, page.getTotalRowCount());
        assertEquals(2, page.getTotalPageCount());

        Slice<Book> slice = sqlClient
                .createQuery(table)
                .orderBy(table.id())
                .select(table)
                .fetchSlice(2, 0);
        assertTrue(slice.isHead());

        List<Tuple2<String, Integer>> tuples = sqlClient
                .createQuery(table)
                .where(table.name().like("Graph", LikeMode.START))
                .select(table.name(), table.edition())
                .execute();
        assertEquals(1, tuples.size());

        Long count = sqlClient
                .createQuery(table)
                .select(table.count())
                .fetchOne();
        assertEquals(3L, count);

        List<Tuple2<Long, BigDecimal>> avgPrices = sqlClient
                .createQuery(table)
                .where(table.storeId().isNotNull())
                .groupBy(table.storeId())
                .select(table.storeId(), table.price().avgAsDecimal())
                .execute();
        assertEquals(2, avgPrices.size());

        // subquery
        AuthorTableEx author = AuthorTableEx.$;
        List<Long> ids = sqlClient
                .createQuery(table)
                .where(
                        table.id().in(
                                sqlClient
                                        .createSubQuery(author)
                                        .where(author.firstName().eq("Alex"))
                                        .select(author.books().id())
                        )
                )
                .select(table.id())
                .execute();
        assertEquals(2, ids.size());

        List<Book> existsBooks = sqlClient
                .createQuery(table)
                .where(
                        sqlClient
                                .createSubQuery(author)
                                .where(author.books().eq(table), author.firstName().eq("Alex"))
                                .exists()
                )
                .select(table)
                .execute();
        assertEquals(2, existsBooks.size());

        // asTableEx join collection + distinct
        List<Long> joinedIds = sqlClient
                .createQuery(table)
                .where(table.asTableEx().authors().firstName().ilike("e"))
                .select(table.id())
                .distinct()
                .execute();
        assertFalse(joinedIds.isEmpty());

        // tuple in subquery: newest editions
        List<Book> newest = sqlClient
                .createQuery(table)
                .where(
                        Expression.tuple(table.name(), table.edition()).in(
                                sqlClient
                                        .createSubQuery(table)
                                        .groupBy(table.name())
                                        .select(table.name(), table.edition().max())
                        )
                )
                .select(table)
                .execute();
        assertFalse(newest.isEmpty());

        // case / coalesce / native sql
        List<Tuple3<String, String, Integer>> rows = sqlClient
                .createQuery(table)
                .select(
                        Expression.string()
                                .caseBuilder()
                                .when(table.price().lt(new BigDecimal(60)), "Cheap")
                                .otherwise("Expensive"),
                        table.store().website().coalesce("N/A"),
                        Expression.numeric().sql(Integer.class, "length(%e)", table.name())
                )
                .execute();
        assertEquals(3, rows.size());

        // typed tuple mapper
        List<StoreStatistics> stats = sqlClient
                .createQuery(table)
                .where(table.storeId().isNotNull())
                .groupBy(table.storeId())
                .select(
                        StoreStatisticsMapper
                                .storeId(table.storeId())
                                .bookCount(Expression.rowCount())
                                .avgPrice(table.price().avgAsDecimal())
                )
                .execute();
        assertEquals(2, stats.size());
    }

    // ---------------- Object fetcher ----------------

    private static final Fetcher<Book> BOOK_DETAIL_FETCHER =
            BOOK_FETCHER
                    .allScalarFields()
                    .tenant(false)
                    .store(
                            BOOK_STORE_FETCHER
                                    .name()
                                    .avgPrice()
                    )
                    .authors(
                            AUTHOR_FETCHER.firstName().lastName(),
                            cfg -> cfg.batch(16).filter(args -> args.orderBy(args.getTable().firstName()))
                    );

    @Test
    public void testFetcher() {
        BookTable table = BookTable.$;
        List<Book> books = sqlClient
                .createQuery(table)
                .where(table.edition().eq(1))
                .select(table.fetch(BOOK_DETAIL_FETCHER))
                .execute();
        assertFalse(books.isEmpty());
        Book first = books.get(0);
        assertTrue(ImmutableObjects.isLoaded(first.store(), BookStoreProps.AVG_PRICE));
        assertFalse(ImmutableObjects.isLoaded(first, BookProps.TENANT));
        System.out.println(books);

        List<Book> joined = sqlClient
                .createQuery(table)
                .select(
                        table.fetch(
                                BookFetcher.$
                                        .name()
                                        .store(ReferenceFetchType.JOIN_ALWAYS, BookStoreFetcher.$.name())
                                        .authorIds()
                        )
                )
                .execute();
        assertFalse(joined.isEmpty());

        TreeNodeTable node = TreeNodeTable.$;
        List<TreeNode> roots = sqlClient
                .createQuery(node)
                .where(node.parentId().isNull())
                .select(
                        node.fetch(
                                TreeNodeFetcher.$
                                        .name()
                                        .recursiveChildNodes(cfg -> cfg.depth(2))
                        )
                )
                .execute();
        assertEquals(1, roots.size());
        assertEquals(2, roots.get(0).childNodes().size());

        TreeNode deep = sqlClient.findById(
                TreeNodeFetcher.$
                        .name()
                        .recursiveChildNodes(cfg -> cfg.recursive(args -> !args.getEntity().name().equals("Clothing"))),
                1L
        );
        assertNotNull(deep);

        Author author = sqlClient.findById(AUTHOR_FETCHER.fullName().sqlFullName(), 1L);
        assertEquals("Eve Procello", author.fullName());
        assertEquals("Eve Procello", author.sqlFullName());

        Book idOnlyAssoc = sqlClient.findById(BOOK_FETCHER.name().store().authors(), 1L);
        assertTrue(ImmutableObjects.isIdOnly(idOnlyAssoc.store()));

        Map<Long, BookStore> storeMap = sqlClient.findMapByIds(BookStore.class, Arrays.asList(1L, 2L));
        assertEquals(2, storeMap.size());

        Book missing = sqlClient.findById(Book.class, 999L);
        assertNull(missing);
        assertThrows(Exception.class, () -> sqlClient.findOneById(Book.class, 999L));

        List<BookStore> stores = sqlClient.getEntities().findAll(
                BOOK_STORE_FETCHER.allScalarFields().books(BOOK_FETCHER.name()),
                BookStoreProps.NAME
        );
        assertEquals(2, stores.size());
    }

    // ---------------- DTO ----------------

    @Test
    public void testDto() {
        BookTable table = BookTable.$;
        List<BookView> views = sqlClient
                .createQuery(table)
                .where(table.edition().eq(1))
                .orderBy(table.name())
                .select(table.fetch(BookView.class))
                .execute();
        assertFalse(views.isEmpty());
        assertNotNull(views.get(0).getStore());

        BookDetailView detail = sqlClient.findById(BookDetailView.class, 1L);
        assertNotNull(detail);
        System.out.println(detail);

        BookSimpleView simple = sqlClient.findById(BookSimpleView.class, 1L);
        assertEquals(1L, simple.getStoreId());
        assertEquals(2, simple.getAuthorIds().size());

        Book entity = sqlClient.findById(BookView.METADATA.getFetcher(), 1L);
        BookView view = new BookView(entity);
        Book back = view.toEntity();
        assertEquals(entity.name(), back.name());

        TreeNodeView tree = sqlClient.findById(TreeNodeView.class, 1L);
        assertNotNull(tree.getChildNodes());

        FlatTreeNodeView flat = sqlClient.findById(FlatTreeNodeView.class, 4L);
        assertEquals("Food", flat.getParentName());

        FlatBookStoreView folded = sqlClient.findById(FlatBookStoreView.class, 1L);
        assertEquals(0, folded.getMeta().getVersion());

        BookCard card = sqlClient.findById(BookCard.class, 1L);
        assertEquals(2, card.getAuthors().size());
        assertNotNull(card.getStore().getName());

        BookSpecification spec = new BookSpecification();
        spec.setName("graphql");
        spec.setMinPrice(new BigDecimal(40));
        spec.setAuthorName("eve");
        List<Book> specBooks = sqlClient
                .createQuery(table)
                .where(spec)
                .select(table)
                .execute();
        assertEquals(2, specBooks.size());
    }

    // ---------------- Save command ----------------

    @Test
    public void testSave() {
        // insert by key (name) because id is not specified; children inserted with REPLACE (default)
        BookStore store = Immutables.createBookStore(draft -> {
            draft.setName("AMAZON");
            draft.addIntoBooks(book -> {
                book.setName("C++ Primer");
                book.setEdition(5);
                book.setPrice(new BigDecimal("44.02"));
            });
            draft.addIntoBooks(book -> {
                book.setName("Programming RUST");
                book.setEdition(1);
                book.setPrice(new BigDecimal("71.99"));
                book.addIntoAuthors(author -> author.setId(1L));
            });
        });
        SimpleSaveResult<BookStore> result = sqlClient.save(store);
        assertTrue(result.isModified());
        long storeId = result.getModifiedEntity().id();
        assertTrue(storeId > 0);
        assertEquals(2, result.getAffectedRowCount(Book.class));
        assertEquals(1, result.getAffectedRowCount(AffectedTable.of(BookProps.AUTHORS)));

        // update only one column, incomplete object
        SimpleSaveResult<Book> updated = sqlClient
                .saveCommand(
                        Immutables.createBook(draft -> {
                            draft.setId(1L);
                            draft.setPrice(new BigDecimal("33.9"));
                        })
                )
                .setMode(SaveMode.UPDATE_ONLY)
                .execute();
        assertEquals(1, updated.getTotalAffectedRowCount());

        // explicit mode + associated mode
        sqlClient.save(
                Immutables.createBook(draft -> {
                    draft.setName("New Book");
                    draft.setEdition(1);
                    draft.setPrice(BigDecimal.TEN);
                    draft.setStoreId(storeId);
                }),
                SaveMode.INSERT_ONLY,
                AssociatedSaveMode.APPEND
        );

        // input DTO
        BookInput input = new BookInput();
        input.setName("DTO Book");
        input.setEdition(1);
        input.setPrice(new BigDecimal("12.5"));
        input.setStoreId(storeId);
        input.setAuthorIds(Arrays.asList(1L, 2L));
        long newId = sqlClient.save(input).getModifiedEntity().id();
        assertTrue(newId > 0);

        CompositeBookStoreInput composite = new CompositeBookStoreInput();
        composite.setName("COMPOSITE");
        CompositeBookStoreInput.TargetOf_books b = new CompositeBookStoreInput.TargetOf_books();
        b.setName("Composite Book");
        b.setEdition(1);
        b.setPrice(BigDecimal.ONE);
        b.setAuthorIds(Collections.singletonList(3L));
        composite.setBooks(Collections.singletonList(b));
        sqlClient.save(composite);

        // command options + returning fetcher
        Book saved = sqlClient
                .saveCommand(input.toEntity())
                .setMode(SaveMode.UPSERT)
                .setAssociatedMode(BookProps.AUTHORS, AssociatedSaveMode.MERGE)
                .setKeyProps(BookProps.NAME, BookProps.EDITION)
                .execute(BOOK_FETCHER.name().authors(AUTHOR_FETCHER.firstName()))
                .getModifiedEntity();
        assertEquals("DTO Book", saved.name());

        // batch
        BatchSaveResult<Book> batch = sqlClient.saveEntities(
                Arrays.asList(
                        Immutables.createBook(d -> { d.setId(1L); d.setPrice(new BigDecimal("1")); }),
                        Immutables.createBook(d -> { d.setId(3L); d.setPrice(new BigDecimal("3")); })
                ),
                SaveMode.UPDATE_ONLY,
                AssociatedSaveMode.REPLACE
        );
        assertEquals(2, batch.getItems().size());

        // optimistic lock
        assertThrows(SaveException.OptimisticLockError.class, () ->
                sqlClient
                        .saveCommand(Immutables.createBookStore(d -> {
                            d.setId(1L);
                            d.setWebsite("https://www.oreilly.com");
                            d.setVersion(999);
                        }))
                        .setMode(SaveMode.UPDATE_ONLY)
                        .execute()
        );

        // NotUnique translation
        assertThrows(SaveException.NotUnique.class, () ->
                sqlClient.save(
                        Immutables.createBookStore(d -> d.setName("MANNING")),
                        SaveMode.INSERT_ONLY,
                        AssociatedSaveMode.APPEND
                )
        );

        // upsert mask & update where & assignment
        sqlClient
                .saveCommand(Immutables.createBook(d -> {
                    d.setName("Learning GraphQL");
                    d.setEdition(1);
                    d.setPrice(new BigDecimal("77"));
                }))
                .setUpsertMask(BookProps.PRICE)
                .execute();
        SimpleSaveResult<Book> conditional = sqlClient
                .saveCommand(Immutables.createBook(d -> {
                    d.setId(3L);
                    d.setPrice(new BigDecimal("1"));
                }))
                .setMode(SaveMode.UPDATE_ONLY)
                .setUpdateWhere(
                        BookTable.class,
                        (t, values) -> values.newNumber(BookProps.PRICE).gt(t.price())
                )
                .execute();
        assertFalse(conditional.isAccepted() && conditional.getTotalAffectedRowCount() > 0);
        sqlClient
                .saveCommand(Immutables.createBook(d -> {
                    d.setId(3L);
                    d.setPrice(new BigDecimal("2.00"));
                }))
                .setMode(SaveMode.UPDATE_ONLY)
                .set(BookTable.class, BookProps.PRICE, (t, values) -> t.price().plus(values.newNumber(BookProps.PRICE)))
                .execute();

        // recursive input
        RecursiveTreeInput treeInput = new RecursiveTreeInput();
        treeInput.setName("Root2");
        RecursiveTreeInput child = new RecursiveTreeInput();
        child.setName("Child");
        child.setChildNodes(Collections.emptyList());
        treeInput.setChildNodes(Collections.singletonList(child));
        // `parent` is part of @Key; children get it implicitly from `childNodes`, the root must set it
        TreeNode root = Immutables.createTreeNode(treeInput.toEntity(), d -> d.setParent(null));
        long rootId = sqlClient.save(root).getModifiedEntity().id();
        assertTrue(rootId > 0);
    }

    // ---------------- Delete / DML ----------------

    @Test
    public void testDeleteAndDml() {
        AuthorTable author = AuthorTable.$;
        int affected = sqlClient
                .createUpdate(author)
                .set(author.firstName(), author.firstName().concat("*"))
                .where(author.firstName().eq("Dan"))
                .execute();
        assertEquals(1, affected);

        // logical delete (Author has @LogicalDeleted)
        DeleteResult logical = sqlClient.deleteById(Author.class, 3L);
        assertEquals(1, logical.getTotalAffectedRowCount());
        assertNull(sqlClient.createQuery(author).where(author.id().eq(3L)).select(author).fetchOneOrNull());
        Author ignored = sqlClient
                .filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))
                .createQuery(author)
                .where(author.id().eq(3L))
                .select(author)
                .fetchOne();
        assertTrue(ignored.deleted());

        // physical delete with dissociate override
        DeleteResult result = sqlClient
                .getEntities()
                .deleteCommand(BookStore.class, 2L)
                .setDissociateAction(BookProps.STORE, DissociateAction.SET_NULL)
                .execute();
        assertEquals(1, result.getAffectedRowCount(BookStore.class));

        BookTable book = BookTable.$;
        int deleted = sqlClient
                .createDelete(book)
                .where(book.name().eq("Effective TypeScript"))
                .execute();
        assertTrue(deleted >= 0);

        DeleteResult treeResult = sqlClient.deleteById(TreeNode.class, 2L, DeleteMode.PHYSICAL);
        assertTrue(treeResult.getAffectedRowCount(TreeNode.class) >= 1);
    }

    // ---------------- Transaction ----------------

    @Test
    public void testTransaction() {
        assertThrows(IllegalStateException.class, () ->
                sqlClient.transaction(() -> {
                    sqlClient.save(
                            Immutables.createBookStore(d -> d.setName("TX")),
                            SaveMode.INSERT_ONLY,
                            AssociatedSaveMode.APPEND
                    );
                    throw new IllegalStateException("rollback");
                })
        );
        BookStoreTable store = BookStoreTable.$;
        assertEquals(0L, sqlClient.createQuery(store).where(store.name().eq("TX")).select(store.count()).fetchOne());
    }
}
