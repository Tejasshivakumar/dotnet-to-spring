package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.Optional;

/** The application entry point, which becomes the {@code @SpringBootApplication} class. */
public class ProgramEntryClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    boolean inProgramFile =
        project.files().stream()
            .filter(f -> fileName(f).equals("Program.cs") || fileName(f).equals("Startup.cs"))
            .anyMatch(f -> f.types().contains(type));
    if (inProgramFile) {
      return Optional.of(ClassRole.PROGRAM_ENTRY);
    }
    boolean hasMain = type.methods().stream().anyMatch(m -> m.name().equals("Main"));
    return hasMain ? Optional.of(ClassRole.PROGRAM_ENTRY) : Optional.empty();
  }

  private static String fileName(SourceFile file) {
    String path = file.path().replace('\\', '/');
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
