package com.example.model;

import com.example.model.common.BaseEntity;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

@Entity
@KeyUniqueConstraint
public interface BookStore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String name();

    @Nullable
    String website();

    @Version
    int version();

    @OneToMany(mappedBy = "store", orderedProps = {
            @OrderedProp("name"),
            @OrderedProp(value = "edition", desc = true)
    })
    List<Book> books();

    @Transient(com.example.runtime.BookStoreAvgPriceResolver.class)
    BigDecimal avgPrice();
}
