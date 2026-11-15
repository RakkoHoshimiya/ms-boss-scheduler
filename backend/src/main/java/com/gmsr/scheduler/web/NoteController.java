package com.gmsr.scheduler.web;

import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.repository.*;
import com.gmsr.scheduler.service.*;
import com.gmsr.scheduler.security.CurrentUser;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.IOException;

@RestController @RequestMapping("/api/notes")
public class NoteController {
 private final NoteRepository notes;
 private final NoteAttachmentRepository attachments;
 private final NoteDraftRepository drafts;
 private final Path root;
 public NoteController(NoteRepository notes, NoteAttachmentRepository attachments, NoteDraftRepository drafts,
   @Value("${gmsr.notes.directory:./data/note-attachments}") String directory) {
  this.notes=notes; this.attachments=attachments; this.drafts=drafts; this.root=Path.of(directory).toAbsolutePath().normalize();
 }
 public record Edit(@NotBlank @Size(max=120) String title, @NotBlank @Size(max=50000) String content, boolean pinned, @Size(max=12) List<@NotBlank String> attachmentIds, Long version, String draftId, Long draftVersion) {
  public Edit(String title,String content,boolean pinned,List<String> attachmentIds,Long version) { this(title,content,pinned,attachmentIds,version,null,null); }
 }
 public record AttachmentView(String id,String name,long size,String mediaType) {}
 public record Detail(Note note,List<AttachmentView> attachments) {}
 private Note active(Note n) { if(n.deletedAt!=null) throw new NotFoundException("便签已移入回收站"); return n; }
 private Note note(Long id) { return active(notes.findById(id).orElseThrow(()->new NotFoundException("便签不存在"))); }
 private Note lockedNote(Long id) { return active(notes.findForUpdate(id).orElseThrow(()->new NotFoundException("便签不存在"))); }
 private void checkVersion(Note note,Long version) {
  if(version==null || note.version!=version) throw new ConflictException("便签已被更新，请关闭编辑后重新打开，避免覆盖其他人的修改");
 }
 public record Pin(boolean pinned, @NotNull Long version) {}
 @PutMapping("/{id}/pin") @Transactional public Note pin(@PathVariable Long id,@Valid @RequestBody Pin request) {
  Note note=lockedNote(id); checkVersion(note,request.version()); note.pinned=request.pinned(); note.updatedAt=Instant.now(); return notes.saveAndFlush(note);
 }
 private AttachmentView view(NoteAttachment a) { return new AttachmentView(a.id,a.name,a.size,a.mediaType); }
 @GetMapping public List<Note> list() { return notes.findByDeletedAtIsNullOrderByPinnedDescUpdatedAtDescIdDesc(); }
 @GetMapping("/trash") public List<Note> trash() { return notes.findByDeletedAtAfterOrderByDeletedAtDesc(Instant.now().minus(Duration.ofDays(7))); }
 @PostMapping("/{id}/restore") @Transactional public Note restore(@PathVariable Long id) {
  Note n=notes.findForUpdate(id).orElseThrow(()->new NotFoundException("便签不存在"));
  if(n.deletedAt==null) return n;
  if(!n.deletedAt.isAfter(Instant.now().minus(Duration.ofDays(7)))) throw new NotFoundException("便签已超过 7 天保留期");
  n.deletedAt=null; n.updatedAt=Instant.now(); return notes.saveAndFlush(n);
 }
 @GetMapping("/{id}") public Detail detail(@PathVariable Long id) { return new Detail(note(id), attachments.findByNoteIdOrderById(id).stream().map(this::view).toList()); }
 @PostMapping @Transactional public Note create(@Valid @RequestBody Edit edit) { return save(new Note(),edit); }
 @PutMapping("/{id}") @Transactional public Note update(@PathVariable Long id,@Valid @RequestBody Edit edit) { Note note=lockedNote(id); checkVersion(note,edit.version()); return save(note,edit); }
 private Note save(Note n,Edit e) {
  NoteDraft draft=e.draftId()==null?null:ownedDraft(e.draftId());
  if(draft!=null) {
   checkDraftVersion(draft,e.draftVersion());
   if(draft.deletedAt!=null || !Objects.equals(draft.noteId,n.id) || !Objects.equals(draft.sourceVersion,e.version())) throw new ConflictException("草稿已删除、已发布或来源版本已改变，请重新打开");
  }
  List<NoteAttachment> selected;
  List<NoteAttachment> previous=n.id==null?List.of():attachments.findByNoteIdOrderById(n.id);
  List<String> selectedIds=e.attachmentIds()==null?previous.stream().map(a->a.id).toList():e.attachmentIds();
  if(new HashSet<>(selectedIds).size()!=selectedIds.size()) throw new BusinessException("附件不能重复");
  var locked=lockAttachments(java.util.stream.Stream.concat(previous.stream().map(a->a.id),selectedIds.stream()).toList());
  selected=selectedIds.stream().sorted().map(id->checkAttachmentAccess(locked.get(id))).toList();
  for(NoteAttachment a:selected) if(a.noteId!=null && !a.noteId.equals(n.id)) throw new BusinessException("附件属于其他便签");
  var marker=java.util.regex.Pattern.compile("\\[\\[image:([a-zA-Z0-9-]+)\\]\\]").matcher(e.content());
  while(marker.find()) {
   String aid=marker.group(1);
   if(selected.stream().noneMatch(a->a.id.equals(aid) && a.mediaType.startsWith("image/"))) throw new BusinessException("正文图片不存在或尚未上传，请重新添加");
  }
  n.title=e.title().trim(); n.content=e.content(); n.pinned=e.pinned(); n.updatedAt=Instant.now();
  Note saved=notes.saveAndFlush(n);
  for(NoteAttachment a:selected) { a.noteId=saved.id; a.retired=false; attachments.save(a); }
  Set<String> kept=selected.stream().map(a->a.id).collect(java.util.stream.Collectors.toSet());
  // Detach on commit; physical deletion is delayed so a failed save cannot break the old note.
  for(NoteAttachment a:previous) if(!kept.contains(a.id)) retire(locked.get(a.id));
  if(draft!=null) { draft.deletedAt=Instant.now(); draft.completed=true; drafts.save(draft); }
  return saved;
 }
 private NoteAttachment accessibleAttachment(String aid) {
  NoteAttachment a=attachments.findById(aid).orElseThrow(()->new NotFoundException("附件不存在"));
  return checkAttachmentAccess(a);
 }
 private NoteAttachment publicationAttachment(String aid) {
  return checkAttachmentAccess(attachments.findForPublication(aid).orElseThrow(()->new NotFoundException("附件不存在")));
 }
 private Map<String,NoteAttachment> lockAttachments(Collection<String> ids) {
  Map<String,NoteAttachment> result=new LinkedHashMap<>();
  // Publication, draft edits and cleanup all take file locks in the same order.
  ids.stream().distinct().sorted().forEach(id->result.put(id,attachments.findForPublication(id).orElseThrow(()->new NotFoundException("附件不存在"))));
  return result;
 }
 private NoteAttachment checkAttachmentAccess(NoteAttachment a) {
  boolean inDraft=CurrentUser.id()!=null && drafts.ownsActiveAttachment(a.id,CurrentUser.id());
  if(a.noteId!=null && !inDraft) note(a.noteId);
  if((a.retired && !inDraft) || (a.noteId==null && !inDraft && (a.createdAt==null || a.createdAt.isBefore(Instant.now().minus(Duration.ofDays(7)))))) throw new NotFoundException("附件已过期或已移除");
  if(a.noteId==null && !inDraft && !Objects.equals(CurrentUser.id(),a.uploadedBy)) throw new NotFoundException("附件不存在");
  return a;
 }
 @PostMapping("/attachments") public synchronized AttachmentView stage(@RequestParam("file") MultipartFile file) throws IOException {
  Long user=CurrentUser.id();
  if(user==null) throw new UnauthorizedException("请先登录");
  if(attachments.countByNoteIdIsNullAndRetiredFalseAndUploadedByAndCreatedAtAfter(user,Instant.now().minus(Duration.ofDays(7)))>=240) throw new BusinessException("待发布附件过多，请先整理草稿和未使用附件");
  return store(null,file);
 }
 @GetMapping("/attachments/{aid}") public ResponseEntity<Resource> stagedDownload(@PathVariable String aid) {
  return resource(accessibleAttachment(aid));
 }
 @DeleteMapping("/attachments/{aid}") @Transactional public ResponseEntity<Void> discard(@PathVariable String aid) throws IOException {
  NoteAttachment a=publicationAttachment(aid);
  if(a.noteId!=null) throw new BusinessException("已发布附件请在编辑便签时移除");
  retire(a); return ResponseEntity.noContent().build();
 }
 @DeleteMapping("/{id}") @Transactional public ResponseEntity<Void> delete(@PathVariable Long id) {
  Note n=notes.findForUpdate(id).orElseThrow(()->new NotFoundException("便签不存在"));
  if(n.deletedAt==null) { n.deletedAt=Instant.now(); notes.saveAndFlush(n); }
  return ResponseEntity.noContent().build();
 }
 @PostMapping("/{id}/attachments") @Transactional public AttachmentView upload(@PathVariable Long id,@RequestParam("file") MultipartFile file) throws IOException {
  Note n=lockedNote(id);
  AttachmentView result=store(id,file);
  n.updatedAt=Instant.now(); notes.saveAndFlush(n);
  return result;
 }
 private AttachmentView store(Long id,MultipartFile file) throws IOException {
  if(file.isEmpty() || file.getSize()>20*1024*1024) throw new BusinessException("附件大小须在 1 字节到 20 MB 之间");
  if(id!=null && attachments.findByNoteIdOrderById(id).size()>=12) throw new BusinessException("每张便签最多 12 个附件");
  String name=Optional.ofNullable(file.getOriginalFilename()).orElse("attachment").replace('\\','/');
  name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "_");
  if(name.isBlank() || name.length()>200) throw new BusinessException("附件名称应为 1 到 200 个字符");
  String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
  Map<String,String> types=Map.of("png","image/png","jpg","image/jpeg","jpeg","image/jpeg","gif","image/gif","webp","image/webp","pdf","application/pdf","txt","text/plain","zip","application/zip");
  if(!types.containsKey(ext)) throw new BusinessException("支持 PNG、JPG、GIF、WebP、PDF、TXT 和 ZIP");
  NoteAttachment a=new NoteAttachment(); a.id=UUID.randomUUID().toString(); a.noteId=id; a.name=name; a.size=file.getSize(); a.uploadedBy=CurrentUser.id(); a.createdAt=Instant.now();
  // Only raster formats decoded by the JDK are rendered inline; other files are downloads.
  a.mediaType="application/octet-stream";
  if(Set.of("png","jpg","jpeg","gif").contains(ext)) {
   try(var input=file.getInputStream()) {
    var reader=javax.imageio.ImageIO.createImageInputStream(input);
    try(reader) {
     var readers=javax.imageio.ImageIO.getImageReaders(reader);
     if(!readers.hasNext()) throw new BusinessException("图片格式无效");
     var decoder=readers.next();
     try { decoder.setInput(reader); if((long)decoder.getWidth(0)*decoder.getHeight(0)>40000000) throw new BusinessException("图片尺寸过大");
      String actual=decoder.getFormatName().toLowerCase(Locale.ROOT);
      a.mediaType=actual.equals("jpeg")?"image/jpeg":actual.equals("png")?"image/png":actual.equals("gif")?"image/gif":"application/octet-stream";
     } finally { decoder.dispose(); }
    }
   }
  }
  a.storageKey=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().replace('-','/')+"/attachment-"+a.id+"."+ext;
  Path path=root.resolve(a.storageKey); Files.createDirectories(path.getParent());
  try { file.transferTo(path); attachments.saveAndFlush(a); }
  catch(Exception e) { Files.deleteIfExists(path); throw e; }
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
   org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
    @Override public void afterCompletion(int status) {
     if(status==STATUS_ROLLED_BACK) try { Files.deleteIfExists(path); } catch(IOException ex) { org.slf4j.LoggerFactory.getLogger(NoteController.class).warn("回滚上传的临时文件清理失败"); }
    }
   });
  }
  return view(a);
 }
 private NoteAttachment attachment(Long id,String aid) {
  note(id);
  NoteAttachment a=attachments.findById(aid).orElseThrow(()->new NotFoundException("附件不存在"));
  if(!Objects.equals(a.noteId,id)) throw new NotFoundException("附件不存在"); return a;
 }
 @GetMapping("/{id}/attachments/{aid}") public ResponseEntity<Resource> download(@PathVariable Long id,@PathVariable String aid) {
  return resource(attachment(id,aid));
 }
 private ResponseEntity<Resource> resource(NoteAttachment a) { Resource resource=new FileSystemResource(root.resolve(a.storageKey));
  if(!resource.exists()) throw new NotFoundException("附件文件不存在");
  return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
   .header("Content-Disposition",ContentDisposition.attachment().filename(a.name,java.nio.charset.StandardCharsets.UTF_8).build().toString())
   .contentType(MediaType.parseMediaType(a.mediaType)).body(resource);
 }
 @DeleteMapping("/{id}/attachments/{aid}") @Transactional public ResponseEntity<Void> deleteAttachment(@PathVariable Long id,@PathVariable String aid) {
  Note n=lockedNote(id);
  NoteAttachment a=publicationAttachment(aid);
  if(!Objects.equals(a.noteId,id)) throw new NotFoundException("附件不存在");
  n.content=n.content.replace("[[image:"+aid+"]]", ""); n.updatedAt=Instant.now(); notes.saveAndFlush(n);
  retire(a); return ResponseEntity.noContent().build();
 }
 private void retire(NoteAttachment a) {
  a.noteId=null; a.retired=true; a.uploadedBy=CurrentUser.id(); a.createdAt=Instant.now(); attachments.save(a);
 }

 public record DraftEdit(Long noteId,Long sourceVersion,Long version,@NotNull @Size(max=120) String title,
   @NotNull @Size(max=50000) String content,boolean pinned,@NotNull @Size(max=12) List<@NotBlank String> attachmentIds) {}
 public record DraftView(String id,long version,Long noteId,Long sourceVersion,String title,String content,boolean pinned,
   Instant updatedAt,Instant deletedAt,List<AttachmentView> attachments) {}
 private DraftView draftView(NoteDraft d) { return new DraftView(d.id,d.version,d.noteId,d.sourceVersion,d.title,d.content,d.pinned,d.updatedAt,d.deletedAt,d.attachments.stream().map(this::view).toList()); }
 private Long draftOwner() { Long id=CurrentUser.id(); if(id==null) throw new UnauthorizedException("请先登录"); return id; }
 private NoteDraft ownedDraft(String id) {
  NoteDraft d=drafts.findForUpdate(id).orElseThrow(()->new NotFoundException("草稿不存在"));
  if(!draftOwner().equals(d.ownerId)) throw new NotFoundException("草稿不存在"); return d;
 }
 private void checkDraftVersion(NoteDraft d,Long version) { if(version==null || version!=d.version) throw new ConflictException("草稿已在其他窗口更新，请保留本地内容后重新打开"); }
 @GetMapping("/drafts") @Transactional(readOnly=true) public List<DraftView> draftList() {
  return drafts.findByOwnerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(draftOwner()).stream().map(this::draftView).toList();
 }
 @GetMapping("/drafts/trash") @Transactional(readOnly=true) public List<DraftView> draftTrash() {
  return drafts.findByOwnerIdAndCompletedFalseAndDeletedAtAfterOrderByDeletedAtDesc(draftOwner(),Instant.now().minus(Duration.ofDays(7))).stream().map(this::draftView).toList();
 }
 @PutMapping("/drafts/{id}") @Transactional public DraftView saveDraft(@PathVariable String id,@Valid @RequestBody DraftEdit e) {
  try { if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
  catch(IllegalArgumentException ex) { throw new BusinessException("草稿编号无效"); }
  Long owner=draftOwner();
  NoteDraft d=drafts.findForUpdate(id).orElse(null);
  if(d==null) {
   if(e.version()!=null) throw new ConflictException("草稿已被清理，请另存为新草稿");
   if(drafts.countByOwnerIdAndDeletedAtIsNull(owner)>=20) throw new BusinessException("最多保留 20 份草稿，请先整理草稿箱");
   d=new NoteDraft(); d.id=id; d.ownerId=owner; d.noteId=e.noteId(); d.sourceVersion=e.sourceVersion();
  } else {
   if(!owner.equals(d.ownerId)) throw new NotFoundException("草稿不存在");
   checkDraftVersion(d,e.version());
   if(d.deletedAt!=null || !Objects.equals(d.noteId,e.noteId()) || !Objects.equals(d.sourceVersion,e.sourceVersion())) throw new ConflictException("草稿已删除、已发布或来源已改变，请重新打开");
  }
  if(e.noteId()!=null) { note(e.noteId()); if(e.sourceVersion()==null) throw new BusinessException("编辑已有攻略须保留来源版本"); }
  if(new HashSet<>(e.attachmentIds()).size()!=e.attachmentIds().size()) throw new BusinessException("附件不能重复");
  var locked=lockAttachments(java.util.stream.Stream.concat(d.attachments.stream().map(a->a.id),e.attachmentIds().stream()).toList());
  List<NoteAttachment> selected=e.attachmentIds().stream().sorted().map(aid->checkAttachmentAccess(locked.get(aid))).toList();
  for(NoteAttachment a:selected) if(a.noteId!=null && !Objects.equals(a.noteId,e.noteId())) throw new BusinessException("附件属于其他便签");
  var marker=java.util.regex.Pattern.compile("\\[\\[image:([a-zA-Z0-9-]+)\\]\\]").matcher(e.content());
  while(marker.find()) { String aid=marker.group(1); if(selected.stream().noneMatch(a->a.id.equals(aid) && a.mediaType.startsWith("image/"))) throw new BusinessException("正文图片不存在，请重新添加"); }
  Set<String> kept=selected.stream().map(a->a.id).collect(java.util.stream.Collectors.toSet());
  for(NoteAttachment previous:d.attachments) if(!kept.contains(previous.id)) {
   var removed=locked.get(previous.id);
   if(removed.noteId==null) retire(removed);
  }
  d.title=e.title(); d.content=e.content(); d.pinned=e.pinned(); d.updatedAt=Instant.now(); d.attachments.clear(); d.attachments.addAll(selected);
  return draftView(drafts.saveAndFlush(d));
 }
 @DeleteMapping("/drafts/{id}") @Transactional public ResponseEntity<Void> deleteDraft(@PathVariable String id) {
  NoteDraft d=ownedDraft(id); if(d.deletedAt==null) { d.deletedAt=Instant.now(); drafts.save(d); } return ResponseEntity.noContent().build();
 }
 @PostMapping("/drafts/{id}/restore") @Transactional public DraftView restoreDraft(@PathVariable String id) {
  NoteDraft d=ownedDraft(id);
  if(d.completed || (d.deletedAt!=null && !d.deletedAt.isAfter(Instant.now().minus(Duration.ofDays(7))))) throw new NotFoundException("草稿已超过保留期或已发布");
  d.deletedAt=null; d.updatedAt=Instant.now(); return draftView(drafts.saveAndFlush(d));
 }
}
