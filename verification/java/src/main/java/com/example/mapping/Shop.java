package com.example.mapping;
import org.babyfish.jimmer.sql.*;
import java.util.List;
@Entity
public interface Shop {
    @Id long id();
    @ManyToMany
    @JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID",
            filter = @JoinTable.JoinTableFilter(columnName = "TYPE", values = "VIP"))
    List<Customer> vipCustomers();
    @ManyToMany
    @JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID",
            readonly = true)
    List<Customer> customers();
    @ManyToMany
    @JoinTable(
            name = "SHOP_FAKE_MAPPING",
            joinColumns = @JoinColumn(name = "SHOP_ID", foreignKeyType = ForeignKeyType.FAKE),
            inverseJoinColumns = @JoinColumn(name = "CUSTOMER_ID", foreignKeyType = ForeignKeyType.FAKE)
    )
    List<Customer> fakeCustomers();
}
