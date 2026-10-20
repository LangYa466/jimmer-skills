package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity @DiscriminatorValue("ORG")
public interface Organization extends Client { String taxCode(); }
