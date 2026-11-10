package com.gmsr.scheduler.domain;
import jakarta.persistence.*;
@Entity @Table(name="note_attachment")
public class NoteAttachment {
 @Id public String id;
 @Column(nullable=false) public Long noteId;
 @Column(nullable=false) public String name;
 @Column(nullable=false) public String storageKey;
 @Column(nullable=false) public long size;
 @Column(nullable=false) public String mediaType;
}
