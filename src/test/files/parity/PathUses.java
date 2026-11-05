package parity; // parity

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.servlet.http.HttpServletRequest;

class PathUses {
  @org.springframework.beans.factory.annotation.Value("${data.dir}")
  private String dir;

  String read(HttpServletRequest request) throws IOException {
    File f = new File(dir, request.getParameter("name"));
    try (FileReader r = new FileReader(f)) { // Noncompliant
      return String.valueOf(r.read());
    }
  }

  int stream(HttpServletRequest request) throws IOException {
    File f = new File(request.getParameter("name"));
    try (FileInputStream in = new FileInputStream(f)) { // Noncompliant
      return in.read();
    }
  }

  String lines(HttpServletRequest request) throws IOException {
    Path p = Paths.get(dir).resolve(request.getParameter("name"));
    return Files.newBufferedReader(p).readLine(); // Noncompliant
  }

  void copy(HttpServletRequest request) throws IOException {
    Files.copy(Path.of(request.getParameter("from")), Path.of(dir, "copy")); // Noncompliant
  }

  File onlyBuilt(HttpServletRequest request) {
    return new File(dir, request.getParameter("name"));
  }
}
