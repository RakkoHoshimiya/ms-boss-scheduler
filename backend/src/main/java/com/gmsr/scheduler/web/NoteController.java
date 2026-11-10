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
 private final Path root;
 public NoteController(NoteRepository notes, NoteAttachmentRepository attachments,
   @Value("${gmsr.notes.directory:./data/note-attachments}") String directory) {
  this.notes=notes; this.attachments=attachments; this.root=Path.of(directory).toAbsolutePath().normalize();
 }
 public record Edit(@NotBlank @Size(max=120) String title, @NotBlank @Size(max=50000) String content, boolean pinned, @Size(max=12) List<String> attachmentIds) {}
 public record AttachmentView(String id,String name,long size,String mediaType) {}
 public record Detail(Note note,List<AttachmentView> attachments) {}
 private Note note(Long id) { return notes.findById(id).orElseThrow(()->new NotFoundException("便签不存在")); }
 private AttachmentView view(NoteAttachment a) { return new AttachmentView(a.id,a.name,a.size,a.mediaType); }
 @GetMapping public List<Note> list() { return notes.findAllByOrderByPinnedDescUpdatedAtDescIdDesc(); }
 @GetMapping("/{id}") public Detail detail(@PathVariable Long id) { return new Detail(note(id), attachments.findByNoteIdOrderById(id).stream().map(this::view).toList()); }
 @PostMapping @Transactional public Note create(@Valid @RequestBody Edit edit) { return save(new Note(),edit); }
 @PutMapping("/{id}") @Transactional public Note update(@PathVariable Long id,@Valid @RequestBody Edit edit) { return save(note(id),edit); }
 private Note save(Note n,Edit e) {
  List<NoteAttachment> selected;
  List<NoteAttachment> previous=n.id==null?List.of():attachments.findByNoteIdOrderById(n.id);
  if(e.attachmentIds()==null) selected=previous;
  else {
   if(new HashSet<>(e.attachmentIds()).size()!=e.attachmentIds().size()) throw new BusinessException("附件不能重复");
   selected=e.attachmentIds().stream().map(this::publicationAttachment).toList();
   for(NoteAttachment a:selected) if(a.noteId!=null && !a.noteId.equals(n.id)) throw new BusinessException("附件属于其他便签");
  }
  var marker=java.util.regex.Pattern.compile("\\[\\[image:([a-zA-Z0-9-]+)\\]\\]").matcher(e.content());
  while(marker.find()) {
   String aid=marker.group(1);
   if(selected.stream().noneMatch(a->a.id.equals(aid) && a.mediaType.startsWith("image/"))) throw new BusinessException("正文图片不存在或尚未上传，请重新添加");
  }
  n.title=e.title().trim(); n.content=e.content(); n.pinned=e.pinned(); n.updatedAt=Instant.now();
  Note saved=notes.saveAndFlush(n);
  for(NoteAttachment a:selected) { a.noteId=saved.id; attachments.save(a); }
  Set<String> kept=selected.stream().map(a->a.id).collect(java.util.stream.Collectors.toSet());
  // Detach on commit; physical deletion is delayed so a failed save cannot break the old note.
  for(NoteAttachment a:previous) if(!kept.contains(a.id)) {a.noteId=null; a.uploadedBy=CurrentUser.id(); a.createdAt=Instant.now(); attachments.save(a);}
  return saved;
 }
 private NoteAttachment accessibleAttachment(String aid) {
  NoteAttachment a=attachments.findById(aid).orElseThrow(()->new NotFoundException("附件不存在"));
  return checkAttachmentAccess(a);
 }
 private NoteAttachment publicationAttachment(String aid) {
  return checkAttachmentAccess(attachments.findForPublication(aid).orElseThrow(()->new NotFoundException("附件不存在")));
 }
 private NoteAttachment checkAttachmentAccess(NoteAttachment a) {
  if(a.noteId==null && (CurrentUser.id()==null || !CurrentUser.id().equals(a.uploadedBy))) throw new NotFoundException("附件不存在");
  return a;
 }
 @PostMapping("/attachments") public synchronized AttachmentView stage(@RequestParam("file") MultipartFile file) throws IOException {
  Long user=CurrentUser.id();
  if(user==null) throw new UnauthorizedException("请先登录");
  if(attachments.countByNoteIdIsNullAndUploadedBy(user)>=24) throw new BusinessException("待发布附件过多，请先保存或取消正在编辑的便签");
  return store(null,file);
 }
 @GetMapping("/attachments/{aid}") public ResponseEntity<Resource> stagedDownload(@PathVariable String aid) {
  return resource(accessibleAttachment(aid));
 }
 @DeleteMapping("/attachments/{aid}") @Transactional public ResponseEntity<Void> discard(@PathVariable String aid) throws IOException {
  NoteAttachment a=publicationAttachment(aid);
  if(a.noteId!=null) throw new BusinessException("已发布附件请在编辑便签时移除");
  removeFile(a); return ResponseEntity.noContent().build();
 }
 @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) throws IOException {
  Note n=note(id);
  for(NoteAttachment a:attachments.findByNoteIdOrderById(id)) removeFile(a);
  notes.delete(n); return ResponseEntity.noContent().build();
 }
 @PostMapping("/{id}/attachments") public synchronized AttachmentView upload(@PathVariable Long id,@RequestParam("file") MultipartFile file) throws IOException {
  note(id);
  return store(id,file);
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
  return view(a);
 }
 private NoteAttachment attachment(Long id,String aid) {
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
 @DeleteMapping("/{id}/attachments/{aid}") public ResponseEntity<Void> deleteAttachment(@PathVariable Long id,@PathVariable String aid) throws IOException {
  NoteAttachment a=attachment(id,aid); removeFile(a); return ResponseEntity.noContent().build();
 }
 private void removeFile(NoteAttachment a) throws IOException { Files.deleteIfExists(root.resolve(a.storageKey)); attachments.delete(a); }
}
