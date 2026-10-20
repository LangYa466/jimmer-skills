package com.example.mapping;
import org.babyfish.jimmer.jackson.Converter;
public class PhoneMaskConverter implements Converter<String, String> {
    @Override public String output(String value) { return value.substring(0, 3) + "****" + value.substring(7); }
    @Override public String input(String value) { return value; }
}
