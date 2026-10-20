package com.example.model;

import org.babyfish.jimmer.sql.TypedTuple;

@TypedTuple
public class CourseRow {
    private final long id;
    private final String name;
    public CourseRow(long id, String name) { this.id = id; this.name = name; }
    public long getId() { return id; }
    public String getName() { return name; }
}
