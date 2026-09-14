package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies classifiers in priority order and returns a project with every type's role filled in.
 *
 * <p>Classification is a whole-project pass because the evidence is spread across files: a class is
 * an entity because some <em>other</em> file's DbContext has a {@code DbSet<T>} of it, and a class
 * is a service because {@code Program.cs} registered it.
 */
public class ClassifierChain {

  private final List<Classifier> classifiers;

  public ClassifierChain(List<Classifier> classifiers) {
    this.classifiers = List.copyOf(classifiers);
  }

  /** The default chain, most specific evidence first. */
  public static ClassifierChain defaults() {
    return new ClassifierChain(
        List.of(
            new ProgramEntryClassifier(),
            new DbContextClassifier(),
            new ControllerClassifier(),
            new EntityClassifier(),
            new ServiceInterfaceClassifier(),
            new ServiceClassifier(),
            new ConfigurationClassifier(),
            new DtoClassifier()));
  }

  /** @return a copy of the project with roles assigned */
  public SourceProject classify(SourceProject project) {
    List<SourceFile> files = new ArrayList<>();
    for (SourceFile file : project.files()) {
      List<TypeDecl> types = new ArrayList<>();
      for (TypeDecl type : file.types()) {
        types.add(type.withRole(roleFor(type, project)));
      }
      files.add(new SourceFile(file.path(), file.namespaceName(), file.usings(), types));
    }
    return new SourceProject(
        project.name(),
        project.targetFramework(),
        project.packages(),
        files,
        project.appSettings(),
        // Carried through: dropping it here would silently disable DI-based
        // classification and fall back to naming conventions without saying so.
        project.entryPointSource());
  }

  ClassRole roleFor(TypeDecl type, SourceProject project) {
    for (Classifier classifier : classifiers) {
      var role = classifier.classify(type, project);
      if (role.isPresent()) {
        return role.get();
      }
    }
    return ClassRole.UNKNOWN;
  }
}
