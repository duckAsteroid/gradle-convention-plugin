import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Confirms duckasteroid-release-flow registers all six of its tasks (see the file-level comment in
 * duckasteroid-release-flow.gradle), including installReleaseWorkflows/checkReleaseWorkflows added
 * for issue #2 - task *actions* aren't exercised here (ProjectBuilder doesn't run doLast blocks;
 * that logic is covered directly by WorkflowInstallerTest/WorkflowCheckerTest instead), just that
 * applying the plugin wires them up correctly.
 */
public class ReleaseFlowPluginTest {

  @Test
  void registersAllSixReleaseTasks() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("duckasteroid-java");
    project.getPluginManager().apply("duckasteroid-release-flow");

    for (String taskName :
        new String[] {
          "tagReleaseCandidate",
          "promoteReleaseCandidate",
          "changelogForReleaseCandidate",
          "changelogForRelease",
          "installReleaseWorkflows",
          "checkReleaseWorkflows"
        }) {
      Task task = project.getTasks().findByName(taskName);
      assertNotNull(task, taskName + " should be registered");
      assertEquals("release", task.getGroup(), taskName + " should be in the 'release' group");
    }
  }
}
