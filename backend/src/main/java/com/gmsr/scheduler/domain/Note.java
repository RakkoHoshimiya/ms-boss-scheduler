package com.gmsr.scheduler.domain;
import jakarta.persistence.*;
@Entity @Table(name="guide_note")
public class Note {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,length=120) public String title;
 @Column(nullable=false,columnDefinition="text") public String content;
 @Column(nullable=false) public boolean pinned;
 @Column(nullable=false) public java.time.Instant updatedAt;
}
