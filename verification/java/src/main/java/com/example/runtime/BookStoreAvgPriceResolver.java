package com.example.runtime;

import com.example.model.Book;
import com.example.model.BookProps;
import com.example.model.BookStore;
import com.example.model.BookStoreProps;
import org.babyfish.jimmer.lang.Ref;
import org.babyfish.jimmer.sql.event.AssociationEvent;
import org.babyfish.jimmer.sql.event.EntityEvent;
import org.jetbrains.annotations.NotNull;
import java.util.Collections;

import com.example.model.BookTable;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.TransientResolver;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public class BookStoreAvgPriceResolver implements TransientResolver<Long, BigDecimal> {

    private final JSqlClient sqlClient;

    public BookStoreAvgPriceResolver(JSqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public Map<Long, BigDecimal> resolve(Collection<Long> ids) {
        BookTable table = BookTable.$;
        return Tuple2.toMap(
                sqlClient
                        .createQuery(table)
                        .where(table.storeId().in(ids))
                        .groupBy(table.storeId())
                        .select(table.storeId(), table.price().avgAsDecimal())
                        .execute()
        );
    }

    @Override
    public BigDecimal getDefaultValue() {
        return BigDecimal.ZERO;
    }

    @Override
    public Collection<?> getAffectedSourceIds(@NotNull AssociationEvent e) {
        if (sqlClient.getCaches().isAffectedBy(e) && e.getImmutableProp() == BookStoreProps.BOOKS.unwrap()) {
            return Collections.singletonList(e.getSourceId());
        }
        return null;
    }

    @Override
    public Collection<?> getAffectedSourceIds(@NotNull EntityEvent<?> e) {
        if (sqlClient.getCaches().isAffectedBy(e) && !e.isEvict() && e.getImmutableType().getJavaClass() == Book.class) {
            Ref<BookStore> storeRef = e.getUnchangedRef(BookProps.STORE);
            if (storeRef != null && storeRef.getValue() != null && e.isChanged(BookProps.PRICE)) {
                return Collections.singletonList(storeRef.getValue().id());
            }
        }
        return null;
    }
}
