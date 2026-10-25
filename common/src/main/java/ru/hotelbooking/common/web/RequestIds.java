package ru.hotelbooking.common.web;

import java.util.UUID;

public final class RequestIds {
    public static final String HEADER = "X-Request-Id";

    private RequestIds() {}

    public static String normalize(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,100}")
                ? value
                : UUID.randomUUID().toString();
    }
}
