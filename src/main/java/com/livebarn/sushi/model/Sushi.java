package com.livebarn.sushi.model;

import jakarta.persistence.*;

@Entity
@Table(name = "sushi")
public class Sushi {
    @Id
    private Integer id;

    private String name;

    @Column(name = "time_to_make")
    private Integer timeToMake;

    public Integer getId(){ return id; }
    public String getName(){ return name; }
    public Integer getTimeToMake() { return timeToMake; }

}
