package com.example.mapping;
import org.babyfish.jimmer.sql.Serialized;
import java.util.List;
@Serialized
public class Settings {
    private List<String> flags;
    public List<String> getFlags() { return flags; }
    public void setFlags(List<String> flags) { this.flags = flags; }
}
