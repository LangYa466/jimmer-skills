package com.example.mapping;
import org.babyfish.jimmer.sql.*;
import java.util.List;
@Entity
public interface Course {
    @Id long id();
    String name();
    @OneToMany(mappedBy = "course") List<LearningLink> learningLinks();
    @ManyToManyView(prop = "learningLinks") List<Student> students();
}
