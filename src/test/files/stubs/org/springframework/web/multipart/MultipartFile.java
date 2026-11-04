package org.springframework.web.multipart;

public interface MultipartFile {
  String getOriginalFilename();

  byte[] getBytes() throws java.io.IOException;
}
