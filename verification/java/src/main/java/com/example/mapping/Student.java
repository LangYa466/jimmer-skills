package com.example.mapping;
import org.babyfish.jimmer.sql.*;
import java.util.List;
@Entity
public interface Student {
    @Id long id();
    String name();
    @OneToMany(mappedBy = "student") List<LearningLink> learningLinks();
    @ManyToManyView(prop = "learningLinks", deeperProp = "course") List<Course> courses();
}
