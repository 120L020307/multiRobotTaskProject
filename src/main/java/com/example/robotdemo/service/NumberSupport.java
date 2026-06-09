package com.example.robotdemo.service;

import java.util.Locale;

public final class NumberSupport {
    private NumberSupport() {}

    public static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return fallback;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) return fallback;
        if (text.endsWith("%")) {
            return parse(text.substring(0, text.length() - 1), fallback);
        }
        return switch (text) {
            case "high", "very_high", "高", "较高", "很高", "true" -> 0.9;
            case "medium", "mid", "normal", "中", "中等", "一般" -> 0.6;
            case "low", "低", "较低", "false" -> 0.3;
            case "unknown", "未知", "null", "none" -> fallback;
            default -> parse(text, fallback);
        };
    }

    public static int toInt(Object value, int fallback) {
        return (int) Math.round(toDouble(value, fallback));
    }

    private static double parse(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
