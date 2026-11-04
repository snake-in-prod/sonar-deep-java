package rules;

class Mailer {
  void send(String to) {
    System.out.println(to.trim());
  }

  void maybe(String to) {
    if (to != null) {
      System.out.println(to.trim());
    }
  }

  void use() {
    send(null); // Noncompliant
    maybe(null);
    send("x");
  }
}

class Builder {
  String build(int code, String data, String type, String detail, String msg) {
    if (code > 0) {
      return detail.trim() + msg;
    }
    return data + type;
  }
}

class Responses {
  private final Builder builder;

  Responses(Builder builder) {
    this.builder = builder;
  }

  String fail() {
    return builder.build(1, "d", "t", null, "m"); // Noncompliant {{"Builder.build()" dereferences its parameter "detail" without a null check, but null is passed here.}}
  }

  String guarded() {
    return new Guarded().build(null);
  }
}

class Guarded {
  String build(String s) {
    if (s == null) {
      return "";
    }
    return s.isEmpty() ? "e" : s;
  }
}

class Formatter {
  String format(String detail) {
    return detail.trim();
  }
}

class Responder {
  private final Formatter formatter = new Formatter();

  String build(int code, String detail) {
    if (code > 0) {
      return formatter.format(detail);
    }
    return "";
  }

  String fail() {
    return build(1, null); // Noncompliant
  }
}




class Responses2 {
  @javax.annotation.Nullable
  String getXml() {
    return System.nanoTime() > 0 ? "<a/>" : null;
  }

  int parse(String xml) {
    return xml.length();
  }

  void use() throws Exception {
    parse(getXml()); // Noncompliant
    String body = getXml();
    new java.io.StringReader(body); // Noncompliant
    org.json.XML.toJSONObject(getXml()); // Noncompliant
    new java.util.HashSet<>(names()); // Noncompliant
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(new byte[0]));
  }

  @javax.annotation.Nullable
  java.util.List<String> names() {
    return null;
  }

  void tenth(String a, String b, String c, String d, String e, String f, String g, String h, String i, String j) {
    System.out.println(a + b + c + d + e + f + g + h + i + j.trim());
  }

  void callTenth() {
    String last = null;
    tenth("a", "b", "c", "d", "e", "f", "g", "h", "i", last); // Noncompliant
  }
}
