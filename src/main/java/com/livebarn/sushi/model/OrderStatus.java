package com.livebarn.sushi.model;

public final class OrderStatus {
    public static final int CREATED = 1;
    public static final int IN_PROGRESS = 2;
    public static final int PAUSED = 3;
    public static final int RESUMED = 4;
    public static final int FINISHED = 5;
    public static final int CANCELLED = 6;

    private OrderStatus() {
    }
}
