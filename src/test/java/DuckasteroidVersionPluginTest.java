import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.conventions.CommitAnalyzer;
import io.github.duckasteroid.conventions.CommitAnalyzerExtension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confirms duckasteroid-version works standalone, with no duckasteroid-java (and no java plugin at
 * all) applied - the whole point of extracting it out of duckasteroid-java.gradle. The
 * ProjectBuilder check covers the structural wiring (extension registered, tagPrefix exported); the
 * functional test proves the extraction actually computes a real Conventional-Commits version
 * end-to-end against a real git repo, the same way an ordinary duckasteroid-java build would.
 */
public class DuckasteroidVersionPluginTest {

  @Test
  void appliesWithoutJavaAndRegistersCommitAnalyzerExtensionAndTagPrefix() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("duckasteroid-version");

    assertFalse(project.getPluginManager().hasPlugin("java"), "no java plugin should be applied");
    assertEquals("v", project.getExtensions().getExtraProperties().get("tagPrefix"));
    assertEquals("", project.getExtensions().getExtraProperties().get("modulePath"));

    CommitAnalyzerExtension extension = project.getExtensions().getByType(CommitAnalyzerExtension.class);
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES, extension.toTypeRules());
  }

  @TempDir Path tempDir;
  private File repo;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    git("init", "-q");
    git("config", "user.email", "test@example.com");
    git("config", "user.name", "Test");
  }

  @Test
  void computesARealConventionalCommitsVersionWithNoJavaPluginPresent() throws Exception {
    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'version-only-fixture'\n");
    // A single-purpose task that prints just project.version - deliberately NOT the built-in
    // `properties` task, which dumps every project property (including any GitHub Packages
    // credentials picked up from ~/.gradle/gradle.properties) into this test's captured output.
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'duckasteroid-version'\n}\n"
            + "tasks.register('printVersion') {\n    doLast { println \"VERSION=${project.version}\" }\n}\n");
    git("add", "-A");
    git("commit", "-q", "-m", "chore: init");
    git("tag", "-a", "v1.0.0", "-m", "v1.0.0");
    git("commit", "--allow-empty", "-q", "-m", "feat: add a thing");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("printVersion", "-q", "--stacktrace")
            .build();

    assertTrue(result.getOutput().contains("VERSION=1.1.0-SNAPSHOT"), "expected a minor bump: " + result.getOutput());
  }

  private void git(String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(repo).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
  }
}
