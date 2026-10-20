package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@EnumType(EnumType.Strategy.ORDINAL)
public enum TicketState { @EnumItem(ordinal = 100) OPEN, @EnumItem(ordinal = 200) DELETED }
