package parity; // parity

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

class Templates {
  @org.springframework.beans.factory.annotation.Value("${templates.dir}")
  private String dir;

  String first(String name) throws IOException {
    String file = dir + "/" + name + ".txt";
    try (BufferedReader r = new BufferedReader(new FileReader(file))) { // Noncompliant
      return r.readLine();
    }
  }
}
