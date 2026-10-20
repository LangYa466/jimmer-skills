package com.example.model;

import com.example.model.common.BaseEntity;
import org.babyfish.jimmer.Formula;
import org.babyfish.jimmer.sql.*;

import java.util.List;

@Entity
public interface Author extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String firstName();

    @Key
    String lastName();

    Gender gender();

    @LogicalDeleted("true")
    boolean deleted();

    @ManyToMany(mappedBy = "authors")
    List<Book> books();

    @Formula(dependencies = {"firstName", "lastName"})
    default String fullName() {
        return firstName() + ' ' + lastName();
    }

    @Formula(sql = "concat(%alias.FIRST_NAME, ' ', %alias.LAST_NAME)")
    String sqlFullName();
}
