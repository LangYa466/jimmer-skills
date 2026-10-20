package com.example.repo;

import com.example.model.Book;
import com.example.model.BookTable;
import org.babyfish.jimmer.spring.repository.DynamicParam;
import org.babyfish.jimmer.spring.repository.JRepository;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface BookJRepository extends JRepository<Book, Long> {

    List<Book> findByNameLikeIgnoreCaseAndPriceBetween(
            @DynamicParam @Nullable String name,
            @DynamicParam @Nullable BigDecimal minPrice,
            @DynamicParam @Nullable BigDecimal maxPrice,
            @Nullable Fetcher<Book> fetcher);

    Page<Book> findByStoreName(@DynamicParam @Nullable String storeName, Pageable pageable, @Nullable Fetcher<Book> fetcher);

    long countByName(String name);

    default List<Book> findCheap(BigDecimal max) {
        BookTable t = BookTable.$;
        return sql().createQuery(t).where(t.price().le(max)).select(t).execute();
    }
}
