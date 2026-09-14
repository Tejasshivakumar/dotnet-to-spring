package com.portway.core.report;

/**
 * The fixed vocabulary of things the migrator can report.
 *
 * <p>Deliberately a closed enum rather than free text: findings that cannot be counted, filtered or
 * asserted on in a test are not worth emitting.
 */
public enum FindingCode {
  /** {@code Task<T>} became {@code T} and {@code await} was dropped. Now synchronous. */
  ASYNC_DROPPED(Severity.MEDIUM),
  /** A C# nullable reference type has no enforced Java equivalent. */
  NULLABLE_REFERENCE(Severity.LOW),
  /** {@code decimal} became {@code BigDecimal}; arithmetic operators do not carry over. */
  DECIMAL_ARITHMETIC(Severity.MEDIUM),
  /** C# {@code byte} is unsigned, Java's is signed. */
  UNSIGNED_BYTE(Severity.MEDIUM),
  /** EF fluent configuration in OnModelCreating was copied to notes, not translated. */
  EF_FLUENT_CONFIG(Severity.HIGH),
  /** A NuGet package has no known Maven equivalent. */
  UNKNOWN_PACKAGE(Severity.HIGH),
  /** An authorization attribute needs matching Spring Security configuration. */
  AUTH_ATTRIBUTE(Severity.HIGH),
  /** A LINQ chain was too complex for the rule engine. */
  LINQ_COMPLEX(Severity.MEDIUM),
  /** A C# construct with no Java equivalent; the member was stubbed. */
  UNSUPPORTED_CONSTRUCT(Severity.HIGH),
  /** ADO.NET and JDBC connection string formats differ. */
  CONNECTION_STRING_FORMAT(Severity.MEDIUM),
  /** Two generated types would share a name. */
  NAME_COLLISION(Severity.HIGH);

  private final Severity defaultSeverity;

  FindingCode(Severity defaultSeverity) {
    this.defaultSeverity = defaultSeverity;
  }

  public Severity defaultSeverity() {
    return defaultSeverity;
  }
}
