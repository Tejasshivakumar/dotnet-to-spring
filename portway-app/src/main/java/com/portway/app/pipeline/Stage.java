package com.portway.app.pipeline;

/**
 * The stages a migration passes through, in order. The progress page shows one row per stage.
 *
 * <p>REPAIR is not a bean of its own: verification and repair alternate inside one loop, and the
 * loop reports the stage it is in as it goes.
 */
public enum Stage {
  INGEST,
  PARSE,
  CLASSIFY,
  PLAN,
  GENERATE,
  AI_FILL,
  VERIFY,
  REPAIR,
  REPORT
}
