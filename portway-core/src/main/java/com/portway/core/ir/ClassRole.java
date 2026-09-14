package com.portway.core.ir;

/**
 * What a type is <em>for</em>, as decided by the classifier after the whole project is parsed.
 *
 * <p>Roles drive generation: a CONTROLLER becomes a {@code @RestController}, an ENTITY becomes a
 * JPA {@code @Entity}, and so on. UNKNOWN types are emitted as plain POJOs and flagged for review
 * rather than guessed at.
 */
public enum ClassRole {
  CONTROLLER,
  ENTITY,
  DB_CONTEXT,
  SERVICE,
  SERVICE_INTERFACE,
  DTO,
  REPOSITORY,
  CONFIGURATION,
  PROGRAM_ENTRY,
  UNKNOWN
}
