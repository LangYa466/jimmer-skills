package com.example.model;

import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@Entity
@KeyUniqueConstraint
public interface BookStore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String name();

    @Nullable
    String website();

    @OneToMany(mappedBy = "store", orderedProps = @OrderedProp("name"))
    List<Book> books();
}
