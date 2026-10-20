package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface Person extends Client { String firstName(); String lastName(); }
