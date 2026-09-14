package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import java.util.Optional;

/**
 * Request and response shapes.
 *
 * <p>Runs last, and the structural half of the rule — properties but no methods — deliberately
 * requires the naming half too. A bare data class that is not named like a DTO is more safely left
 * UNKNOWN and flagged than silently treated as one.
 */
public class DtoClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    if (type.kind() != TypeKind.CLASS && type.kind() != TypeKind.RECORD) {
      return Optional.empty();
    }
    boolean namedLikeDto =
        type.name().endsWith("Dto")
            || type.name().endsWith("Request")
            || type.name().endsWith("Response");
    return namedLikeDto ? Optional.of(ClassRole.DTO) : Optional.empty();
  }
}
