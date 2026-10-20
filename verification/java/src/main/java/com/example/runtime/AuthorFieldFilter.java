package com.example.runtime;

import com.example.model.AuthorTable;
import org.babyfish.jimmer.sql.fetcher.FieldFilter;
import org.babyfish.jimmer.sql.fetcher.FieldFilterArgs;

public class AuthorFieldFilter implements FieldFilter<AuthorTable> {
    @Override
    public void apply(FieldFilterArgs<AuthorTable> args) {
        AuthorTable table = args.getTable();
        args.where(table.firstName().ilike("e"));
        args.orderBy(table.lastName());
    }
}
