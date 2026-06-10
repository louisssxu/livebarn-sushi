package com.livebarn.sushi.model;

public class Chef {
    public static final int COUNT = 3;

    private final int id;

    public Chef(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
