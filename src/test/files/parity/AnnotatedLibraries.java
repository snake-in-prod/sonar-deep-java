package parity; // parity

import java.util.List;
import org.opensaml.saml.saml2.core.Assertion;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

class Reactive {
  String fetch(Mono<String> m) {
    return m.block();
  }

  String fetchLocal(Mono<String> m) {
    String v = m.block();
    return v;
  }
}

class AnnotatedLibraries {
  private final Reactive reactive = new Reactive();

  String direct(Mono<String> m) {
    return m.block().trim(); // Noncompliant
  }

  long inLambda(List<Mono<String>> ms) {
    return ms.stream().map(m -> m.block().trim()).count(); // Noncompliant
  }

  String wrapper(Mono<String> m) {
    return reactive.fetch(m).trim(); // Noncompliant
  }

  String wrapperLocal(Mono<String> m) {
    return reactive.fetchLocal(m).trim(); // Noncompliant
  }

  String upload(MultipartFile f) {
    return f.getOriginalFilename().toLowerCase(); // Noncompliant
  }

  String subject(Assertion a) {
    return a.getSubject().getNameID().getValue(); // Noncompliant 2
  }
}
