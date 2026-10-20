package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public interface Client {
    @Id long id();
    @Discriminator @Column(name = "CLIENT_TYPE") String type();
    String name();
}
