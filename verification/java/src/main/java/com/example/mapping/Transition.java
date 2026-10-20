package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Entity
public interface Transition {
    @Id long id();
    Rect source();
    @PropOverride(prop = "leftTop.x", columnName = "TARGET_LEFT")
    @PropOverride(prop = "leftTop.y", columnName = "TARGET_TOP")
    @PropOverride(prop = "rightBottom.x", columnName = "TARGET_RIGHT")
    @PropOverride(prop = "rightBottom.y", columnName = "TARGET_BOTTOM")
    Rect target();
}
