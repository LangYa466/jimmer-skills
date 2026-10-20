package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface MessageDelivery {
    @Id long messageId();
    String status();
    @OneToOne @MapsId @JoinColumn Message message();
}
