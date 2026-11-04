package nullness;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
@interface Nullable {
}

@lombok.Data
class Response {
  @Nullable
  private String replyXml;
  private String id;
}

class Reader {
  @Nullable
  String find(String k) {
    return k.isEmpty() ? "x" : k;
  }

  int use(Response response) {
    int n = response.getReplyXml().length(); // Noncompliant {{"Response.getReplyXml()" can return null (returns the @Nullable field Response.replyXml); check the result before dereferencing it.}}
    n += response.getId().length();
    n += find("k").length(); // Noncompliant {{"Reader.find()" can return null (annotated as nullable); check the result before dereferencing it.}}
    String checked = response.getReplyXml();
    if (checked != null) {
      n += checked.length();
    }
    return n;
  }
}
