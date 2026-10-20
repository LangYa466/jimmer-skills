package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface Customer { @Id long id(); String name(); }
