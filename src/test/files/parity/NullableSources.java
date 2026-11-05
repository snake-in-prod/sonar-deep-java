package parity; // parity

import java.util.List;
import org.apache.commons.lang3.SerializationUtils;
import org.example.gen.NodeModel;
import org.example.gen.ReplyResponse;
import reactor.core.publisher.Mono;

class Reply {
  @javax.annotation.Nullable
  private List<String> content;

  public List<String> getContent() {
    return content;
  }
}

@lombok.Getter
class Lomboked {
  @javax.annotation.Nullable
  private String note;
}

class Handlers {
  int library(ReplyResponse r) {
    return r.getReplyContent().size(); // Noncompliant
  }

  boolean openApi(NodeModel n) {
    return n.getVersion().startsWith("1."); // Noncompliant
  }

  int project(Reply r) {
    return r.getContent().size(); // Noncompliant
  }

  int lombok(Lomboked l) {
    return l.getNote().length(); // Noncompliant
  }

  String reactive(Mono<String> m) {
    return m.block().trim(); // Noncompliant
  }

  long inLambda(List<Mono<String>> ms) {
    return ms.stream().map(m -> m.block().trim()).count(); // Noncompliant
  }

  int copy(java.util.ArrayList<String> items) {
    return SerializationUtils.clone(items).size(); // Noncompliant
  }

  int checked(Reply r) {
    return r.getContent() == null ? 0 : r.getContent().size();
  }
}
