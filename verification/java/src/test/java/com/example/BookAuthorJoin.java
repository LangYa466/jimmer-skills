package com.example;

import com.example.model.AuthorTable;
import com.example.model.BookTable;
import org.babyfish.jimmer.sql.ast.Predicate;
import org.babyfish.jimmer.sql.ast.table.WeakJoin;

public class BookAuthorJoin implements WeakJoin<BookTable, AuthorTable> {
    @Override
    public Predicate on(BookTable source, AuthorTable target) {
        return source.name().eq(target.lastName());
    }
}
