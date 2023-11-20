import static org.junit.jupiter.api.Assertions.*;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

public class JavaConventionsPluginTest {
  @Test
  public void verifyBasicConventions() {
    Project project = ProjectBuilder.builder().build();
    project.getPluginManager().apply("duckasteroid-java");
    List<Plugin> plugins = project.getPlugins().stream().toList();
    assertEquals(13, plugins.size());
    assertTrue(project.getPluginManager().hasPlugin("java"));
  }
}
