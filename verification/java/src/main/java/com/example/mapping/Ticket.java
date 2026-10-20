package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface Ticket {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "TICKET_ID_SEQ") long id();
    @LogicalDeleted long deletedMillis();
}
