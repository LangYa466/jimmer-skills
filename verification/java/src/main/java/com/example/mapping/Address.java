package com.example.mapping;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;
@Entity
public interface Address {
    @Id long id();
    @Nullable @OneToOne(mappedBy = "address") Member member();
}
