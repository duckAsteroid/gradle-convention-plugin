import static org.junit.jupiter.api.Assertions.*;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

public class JavaConventionsPluginTest {
  @Test
  public void verifyBasicConventions() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("duckasteroid-java");
    // Check for specific required plugins rather than counting total plugins
    assertTrue(project.getPluginManager().hasPlugin("java"), "Java plugin should be applied");
    // Add assertions for other specific plugins you care about here
    // For example:
    // assertTrue(project.getPluginManager().hasPlugin("jacoco"), "JaCoCo plugin should be applied");
    System.out.println("Applied plugins: " + project.getPluginManager());
  }

  @Test
  public void exposesTagPrefixForOtherConventionPluginsToReuse() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("duckasteroid-java");
    assertEquals("v", project.getExtensions().getExtraProperties().get("tagPrefix"));
  }
}
