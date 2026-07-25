import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.duckasteroid.conventions.CommitAnalyzer;
import io.github.duckasteroid.conventions.CommitAnalyzer.Bump;
import io.github.duckasteroid.conventions.CommitAnalyzerExtension;
import java.util.Map;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Exercises the commitAnalyzer { } extension registered by duckasteroid-java.gradle: its defaults
 * should mirror CommitAnalyzer.DEFAULT_TYPE_RULES exactly, and consumers should be able to append to
 * or replace each of the four SetProperty<String>s independently via standard Gradle Property
 * semantics (.add()/.addAll() to append, .set() to replace).
 */
public class CommitAnalyzerExtensionTest {

  private CommitAnalyzerExtension extensionFor(Project project) {
    project.getPluginManager().apply("duckasteroid-java");
    return project.getExtensions().getByType(CommitAnalyzerExtension.class);
  }

  @Test
  void defaultsMirrorCommitAnalyzerDefaultTypeRules() {
    Project project = ProjectBuilder.builder().withName("test").build();
    CommitAnalyzerExtension extension = extensionFor(project);

    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.MAJOR), extension.getMajorTypes().get());
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.MINOR), extension.getMinorTypes().get());
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.PATCH), extension.getPatchTypes().get());
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.NONE), extension.getNoBumpTypes().get());
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES, extension.toTypeRules());
  }

  @Test
  void addAppendsToTheConventionDefaultRatherThanReplacingIt() {
    Project project = ProjectBuilder.builder().withName("test").build();
    CommitAnalyzerExtension extension = extensionFor(project);

    extension.getMinorTypes().add("feat2");

    Set<String> minorTypes = extension.getMinorTypes().get();
    assertEquals(Set.of("feat", "feat2"), minorTypes, "should contain the default 'feat' PLUS the appended type");
  }

  @Test
  void setReplacesTheConventionDefaultEntirely() {
    Project project = ProjectBuilder.builder().withName("test").build();
    CommitAnalyzerExtension extension = extensionFor(project);

    extension.getNoBumpTypes().set(Set.of("docs"));

    assertEquals(Set.of("docs"), extension.getNoBumpTypes().get(), "should NOT contain chore/style/etc from the default");
  }

  @Test
  void eachOfTheFourPropertiesCanBeConfiguredIndependently() {
    Project project = ProjectBuilder.builder().withName("test").build();
    CommitAnalyzerExtension extension = extensionFor(project);

    extension.getMajorTypes().add("security");
    extension.getPatchTypes().add("hotfix");
    // minorTypes/noBumpTypes left untouched - should still be at their defaults.

    Map<Bump, Set<String>> rules = extension.toTypeRules();
    assertEquals(Set.of("security"), rules.get(Bump.MAJOR));
    assertEquals(Set.of("fix", "perf", "hotfix"), rules.get(Bump.PATCH));
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.MINOR), rules.get(Bump.MINOR));
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.NONE), rules.get(Bump.NONE));
  }
}
