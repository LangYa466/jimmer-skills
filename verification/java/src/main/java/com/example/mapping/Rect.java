package com.example.mapping;
import org.babyfish.jimmer.sql.*;
@Embeddable
public interface Rect {
    @PropOverride(prop = "x", columnName = "LEFT_X")
    @PropOverride(prop = "y", columnName = "TOP_Y")
    Point leftTop();
    @PropOverride(prop = "x", columnName = "RIGHT_X")
    @PropOverride(prop = "y", columnName = "BOTTOM_Y")
    Point rightBottom();
}
