package nullness;

import io.kubernetes.client.openapi.models.V1Pod;
import org.example.lib.Interceptor;
import org.example.lib.LibClient;
import org.example.lib.LibRecord;
import org.example.lib.defaults.Repo;
import org.opensaml.saml.saml2.core.Assertion;
import reactor.core.publisher.Mono;

class LibraryNullable {
  int annotatedInJar(LibClient c) {
    return c.find().length() + c.always().length(); // Noncompliant {{"LibClient.find()" can return null (annotated as nullable); check the result before dereferencing it.}}
  }

  int getterOfNullableLibraryField(LibRecord r) {
    return r.getName().length(); // Noncompliant {{"LibRecord.getName()" can return null (returns the @Nullable field LibRecord.name); check the result before dereferencing it.}}
  }

  int packageDefault(Repo r) {
    return r.load().length() + r.loadNonNull().length(); // Noncompliant
  }

  int saml(Assertion a) {
    return a.getSubject().getNameID().getValue().length(); // Noncompliant 2
  }

  int kubernetes(V1Pod pod) {
    return pod.getMetadata().getName().length(); // Noncompliant
  }

  int reactor(Mono<String> m) {
    return m.block().length(); // Noncompliant
  }

  void kubernetesCount(io.kubernetes.client.openapi.models.V1Item item) {
    item.setCount(item.getCount() + 1); // Noncompliant
  }

  int blockedLocal(Mono<java.util.List<String>> m) {
    java.util.List<String> lstData = m.block();
    System.out.printf("%s%n", "loaded");
    System.out.printf("%d%n", lstData.size()); // Noncompliant
    return 0;
  }
}

class Handler implements Interceptor {
  @Override
  public void postHandle(String request, Object model) {
    request.length();
    model.toString(); // Noncompliant {{Parameter "model" is annotated nullable in "Interceptor.postHandle()", which this method overrides; check it before dereferencing it.}}
  }
}

class CheckedHandler implements Interceptor {
  @Override
  public void postHandle(String request, Object model) {
    if (model != null) {
      model.toString();
    }
  }
}

@lombok.Data
class Envelope {
  private Body body;
}

@lombok.Data
class Body {
  @javax.annotation.Nullable
  private String xml;
}

class Chains {
  int chain(Envelope e) {
    return e.getBody().getXml().length(); // Noncompliant
  }
}
