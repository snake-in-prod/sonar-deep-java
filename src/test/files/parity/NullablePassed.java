package parity; // parity

import java.util.List;
import org.example.gen.NodeModel;
import org.example.gen.ReplyResponse;

class Consumers {
  int count(List<String> items) {
    return items.size();
  }

  boolean release(String version) {
    return version.startsWith("1.");
  }

  int library(ReplyResponse r) {
    return count(r.getReplyContent()); // Noncompliant
  }

  boolean openApi(NodeModel n) {
    return release(n.getVersion()); // Noncompliant
  }

  boolean viaLocal(NodeModel n) {
    String v = n.getVersion();
    return release(v); // Noncompliant
  }

  boolean checked(NodeModel n) {
    String v = n.getVersion();
    return v != null && release(v);
  }
}
