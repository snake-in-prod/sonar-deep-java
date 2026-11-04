package org.example.lib.defaults;

public class Repo {
  public String load() {
    return "x";
  }

  @org.example.lib.NonNull
  public String loadNonNull() {
    return "x";
  }
}
