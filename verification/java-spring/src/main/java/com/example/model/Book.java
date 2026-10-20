package com.example.model;

import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A book.
 */
@Entity
@KeyUniqueConstraint
public interface Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    /** Book name */
    @Key
    String name();

    @Key
    int edition();

    BigDecimal price();

    LocalDateTime modifiedTime();

    @Nullable
    @ManyToOne
    BookStore store();

    @ManyToMany
    @JoinTable(name = "BOOK_AUTHOR_MAPPING", joinColumnName = "BOOK_ID", inverseJoinColumnName = "AUTHOR_ID")
    List<Author> authors();

    @IdView("authors")
    List<Long> authorIds();
}
