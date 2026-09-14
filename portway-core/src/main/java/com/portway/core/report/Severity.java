package com.portway.core.report;

/** How much attention a {@link Finding} needs from the human reviewer. */
public enum Severity {
  /** The generated code is wrong or absent and a human must act. */
  HIGH,
  /** The generated code compiles but the semantics changed. */
  MEDIUM,
  /** Worth a glance: a convention or naming difference. */
  LOW,
  /** Purely informational. */
  INFO
}
