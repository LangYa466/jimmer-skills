package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface Message { @Id long id(); String text(); }
