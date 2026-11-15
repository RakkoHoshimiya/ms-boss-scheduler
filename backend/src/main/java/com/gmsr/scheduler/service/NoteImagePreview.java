package com.gmsr.scheduler.service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Set;
import javax.imageio.*;

/** Small, bounded in-memory derivatives. Originals and attachment backups stay unchanged. */
public final class NoteImagePreview {
 private static final int MAX_EDGE=1600, MAX_CACHE_BYTES=8*1024*1024;
 private final Path root;
 private final LinkedHashMap<Path,Cached> cache=new LinkedHashMap<>(16,.75f,true);
 private int cacheBytes;
 public record Preview(byte[] bytes,String mediaType) {}
 private record Cached(long size,java.nio.file.attribute.FileTime modified,Preview preview) {}
 public NoteImagePreview(Path root) { this.root=root.toAbsolutePath().normalize(); }

 // Serialize generation to keep decoded image memory bounded on the small production host.
 public synchronized Preview load(String key,String mediaType) throws IOException {
  if(!Set.of("image/png","image/jpeg","image/gif").contains(mediaType)) throw new BusinessException("此附件不支持图片预览");
  Path file=root.resolve(key).normalize();
  if(!file.startsWith(root) || file.equals(root) || !Files.isRegularFile(file)) throw new NotFoundException("附件文件不存在");
  BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class);
  Cached previous=cache.get(file);
  if(previous!=null && previous.size==attrs.size() && previous.modified.equals(attrs.lastModifiedTime())) return previous.preview;
  if(previous!=null) { cache.remove(file);cacheBytes-=previous.preview.bytes.length; }
  Preview result=generate(file,mediaType);
  if(result.bytes.length<=MAX_CACHE_BYTES) {
   while(cacheBytes+result.bytes.length>MAX_CACHE_BYTES && !cache.isEmpty()) {
    var oldest=cache.entrySet().iterator();cacheBytes-=oldest.next().getValue().preview.bytes.length;oldest.remove();
   }
   cache.put(file,new Cached(attrs.size(),attrs.lastModifiedTime(),result));cacheBytes+=result.bytes.length;
  }
  return result;
 }
 private Preview generate(Path file,String mediaType) throws IOException {
  BufferedImage decoded;
  try(var input=ImageIO.createImageInputStream(file.toFile())) {
   var readers=ImageIO.getImageReaders(input);
   if(!readers.hasNext()) throw new BusinessException("图片格式无效");
   var reader=readers.next();
   try {
    reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);
    if(width<=0 || height<=0 || (long)width*height>40000000) throw new BusinessException("图片尺寸过大");
    var parameters=reader.getDefaultReadParam();
    int sample=Math.max(1,Math.max(width,height)/MAX_EDGE);
    parameters.setSourceSubsampling(sample,sample,0,0);
    decoded=reader.read(0,parameters);
   } finally { reader.dispose(); }
  }
  double scale=Math.min(1,(double)MAX_EDGE/Math.max(decoded.getWidth(),decoded.getHeight()));
  BufferedImage resized=new BufferedImage(Math.max(1,(int)Math.round(decoded.getWidth()*scale)),Math.max(1,(int)Math.round(decoded.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
  Graphics2D graphics=resized.createGraphics();
  try {
   graphics.setColor(Color.WHITE);graphics.fillRect(0,0,resized.getWidth(),resized.getHeight());
   graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
   graphics.drawImage(decoded,0,0,resized.getWidth(),resized.getHeight(),null);
  } finally { graphics.dispose();decoded.flush(); }
  byte[] bytes;
  try(var output=new ByteArrayOutputStream();var stream=ImageIO.createImageOutputStream(output)) {
   var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
   try {
    writer.setOutput(stream);var parameters=writer.getDefaultWriteParam();
    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);parameters.setCompressionQuality(.82f);
    writer.write(null,new IIOImage(resized,null,null),parameters);stream.flush();bytes=output.toByteArray();
   } finally { writer.dispose();resized.flush(); }
  }
  // A small source can already be more efficient; never enlarge its transfer.
  if(Files.size(file)<=bytes.length) return new Preview(Files.readAllBytes(file),mediaType);
  return new Preview(bytes,"image/jpeg");
 }
}
