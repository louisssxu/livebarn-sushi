package com.livebarn.sushi.model;

import jakarta.persistence.*;
import java.sql.Timestamp;


@Entity
@Table(name ="sushi_order")
public class SushiOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "status_id")
    private Integer statusId;

    @Column(name = "sushi_id")
    private Integer sushiId;

    @Column(name = "createdAt")
    private Timestamp createdAt;

    public Integer getId() {return id;}
    public Integer getStatusId() {return statusId; }
    public  Integer getSushiId() {return sushiId; }
    public  Timestamp getTimestamp() {return createdAt; }

    public void setStatusId( Integer statusId ) {
        this.statusId = statusId;
    }
    public void setSushiId( Integer sushiId ) {
        this.sushiId = sushiId;
    }
    public void setCreatedAt( Timestamp createdAt ) {
        this.createdAt = createdAt;
    }



}
