package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface Job {
    @Id long id();
    @Default("OPEN") @LogicalDeleted("DELETED") TicketState state();
}
