package com.gmsr.scheduler.domain;
import jakarta.persistence.*;
@Entity @Table(name="note_attachment")
public class NoteAttachment {
 @Id public String id;
 @Column public Long noteId;
 @Column public Long uploadedBy;
 @Column(nullable=false) public java.time.Instant createdAt;
 @Column(nullable=false) public String name;
 @Column(nullable=false) public String storageKey;
 @Column(nullable=false) public long size;
 @Column(nullable=false) public String mediaType;
}
