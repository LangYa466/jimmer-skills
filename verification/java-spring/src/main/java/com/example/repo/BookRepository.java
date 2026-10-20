package com.example.repo;

import com.example.model.Book;
import com.example.model.BookTable;
import org.babyfish.jimmer.Page;
import org.babyfish.jimmer.spring.repo.PageParam;
import org.babyfish.jimmer.spring.repo.support.AbstractJavaRepository;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class BookRepository extends AbstractJavaRepository<Book, Long> {

    private static final BookTable table = BookTable.$;

    public BookRepository(JSqlClient sql) { super(sql); }

    public Page<Book> findBooks(PageParam page, @Nullable String name, @Nullable Fetcher<Book> fetcher) {
        return sql.createQuery(table)
                .where(table.name().ilikeIf(name))
                .orderBy(table.name().asc(), table.edition().desc())
                .select(table.fetch(fetcher))
                .fetchPage(page.getIndex(), page.getSize());
    }
}
