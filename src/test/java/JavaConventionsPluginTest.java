import static org.junit.jupiter.api.Assertions.*;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

public class JavaConventionsPluginTest {
  @Test
  public void greeterPluginAddsGreetingTaskToProject() {
    Project project = ProjectBuilder.builder().build();
    project.getPluginManager().apply("org.example.greeting");

    assertTrue(project.getTasks().getByName("hello") instanceof GreetingTask);
  }
}
