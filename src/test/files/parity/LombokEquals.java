package parity; // parity

@lombok.EqualsAndHashCode(onlyExplicitlyIncluded = true) // Noncompliant
class NoIncluded {
  private String id;
}

@lombok.EqualsAndHashCode(onlyExplicitlyIncluded = true)
class WithIncluded {
  @lombok.EqualsAndHashCode.Include
  private String id;
}
