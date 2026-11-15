package com.gmsr.scheduler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name="note_draft")
public class NoteDraft {
 @Id @Column(length=36) public String id;
 @Version public long version;
 @Column(nullable=false) public Long ownerId;
 public Long noteId;
 public Long sourceVersion;
 @Column(nullable=false,length=120) public String title="";
 @Column(nullable=false,columnDefinition="text") public String content="";
 public boolean pinned;
 @Column(nullable=false) public Instant updatedAt;
 public Instant deletedAt;
 public boolean completed;
 @ManyToMany
 @JoinTable(name="note_draft_attachment",joinColumns=@JoinColumn(name="draft_id"),inverseJoinColumns=@JoinColumn(name="attachment_id"))
 public List<NoteAttachment> attachments=new ArrayList<>();
}
