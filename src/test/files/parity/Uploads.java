package parity; // parity

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

class Storage {
  void save(MultipartFile file) throws java.io.IOException {
    java.nio.file.Files.write(java.nio.file.Path.of("/data", file.getOriginalFilename()), file.getBytes()); // Noncompliant
  }
}

class UploadController {
  private final Storage storage = new Storage();

  void upload(@RequestParam("file") MultipartFile file) throws java.io.IOException {
    storage.save(file); // Noncompliant
  }
}
