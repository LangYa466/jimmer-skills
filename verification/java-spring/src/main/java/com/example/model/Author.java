package com.example.model;

import org.babyfish.jimmer.sql.*;

import java.util.List;

@Entity
@KeyUniqueConstraint
public interface Author {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String firstName();

    @Key
    String lastName();

    @ManyToMany(mappedBy = "authors")
    List<Book> books();
}
