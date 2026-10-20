package com.example.model;

import com.example.model.common.BaseEntity;
import com.example.model.common.TenantAware;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

@Entity
@KeyUniqueConstraint
public interface Book extends BaseEntity, TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String name();

    @Key
    int edition();

    BigDecimal price();

    @Nullable
    @ManyToOne
    @OnDissociate(DissociateAction.SET_NULL)
    BookStore store();

    @ManyToMany(orderedProps = {
            @OrderedProp("firstName"),
            @OrderedProp("lastName")
    })
    @JoinTable(
            name = "BOOK_AUTHOR_MAPPING",
            joinColumnName = "BOOK_ID",
            inverseJoinColumnName = "AUTHOR_ID"
    )
    List<Author> authors();

    @IdView
    Long storeId();

    @IdView("authors")
    List<Long> authorIds();
}
