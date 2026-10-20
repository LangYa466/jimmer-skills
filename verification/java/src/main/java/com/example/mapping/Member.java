package com.example.mapping;
import org.babyfish.jimmer.Formula;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;
import java.util.List;
@Entity
public interface Member {
    @Id long id();
    String firstName();
    String lastName();
    @OneToOne @JoinColumn(name = "ADDRESS_ID") Address address();
    @Nullable @ManyToOne @JoinColumn(foreignKeyType = ForeignKeyType.FAKE) Member referrer();
    @ManyToMany @JoinSql("concatenation_string_contains(%alias.FRIEND_IDS, %target_alias.ID)") List<Member> friends();
    @Formula(dependencies = "friends") default int friendCount() { return friends().size(); }
    @Formula(sql = "concat(%alias.FIRST_NAME, ' ', %alias.LAST_NAME)") String fullName();
    @Transient Object uiState();
}
