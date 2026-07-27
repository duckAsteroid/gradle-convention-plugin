import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Confirms duckasteroid-release-flow registers all eight of its tasks (see the file-level comment
 * in duckasteroid-release-flow.gradle): the four original per-project tasks, plus the
 * tagReleaseCandidates/promoteReleaseCandidates aggregators and the now-rootProject-scoped
 * installReleaseWorkflows/checkReleaseWorkflows (see MULTI_MODULE_RELEASE_FLOW.md) - task *actions*
 * aren't exercised here (ProjectBuilder doesn't run doLast blocks, and the aggregators' doLast
 * wiring is deferred to a gradle.projectsEvaluated callback that never fires under ProjectBuilder
 * anyway; that logic is covered directly by WorkflowInstallerTest/WorkflowCheckerTest/
 * ReleaseManifestTest instead), just that applying the plugin wires them all up correctly. The
 * project built here has no parent, so it IS its own rootProject - the same tasks container the
 * plugin registers the four root-scoped tasks on.
 */
public class ReleaseFlowPluginTest {

  @Test
  void registersAllEightReleaseTasks() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("duckasteroid-java");
    project.getPluginManager().apply("duckasteroid-release-flow");

    for (String taskName :
        new String[] {
          "tagReleaseCandidate",
          "promoteReleaseCandidate",
          "changelogForReleaseCandidate",
          "changelogForRelease",
          "tagReleaseCandidates",
          "promoteReleaseCandidates",
          "installReleaseWorkflows",
          "checkReleaseWorkflows"
        }) {
      Task task = project.getTasks().findByName(taskName);
      assertNotNull(task, taskName + " should be registered");
      assertEquals("release", task.getGroup(), taskName + " should be in the 'release' group");
    }
  }
}
