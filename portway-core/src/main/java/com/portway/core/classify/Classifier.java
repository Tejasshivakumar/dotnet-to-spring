package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.Optional;

/**
 * One rule for deciding what a type is for.
 *
 * <p>A chain of small classifiers rather than one method with a long if-else: each rule is unit
 * testable on its own, priority is explicit in the chain order, and supporting a new framework
 * means adding a class rather than editing a tangle.
 */
public interface Classifier {

  /** @return the role this rule is confident about, or empty to defer to the next rule */
  Optional<ClassRole> classify(TypeDecl type, SourceProject project);
}
