import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Answers the question that started this whole extraction: can duckasteroid-release-flow be
 * adopted without duckasteroid-java? Applying duckasteroid-version + duckasteroid-release-flow -
 * with no duckasteroid-java, and no java plugin at all - now works for every task except
 * installReleaseWorkflows, which still needs a java-toolchain-configuring plugin present to know
 * what Java version to put in the installed workflow's setup-java step. Both outcomes are exercised
 * here rather than only the happy path, since "install still needs Java" is a real, documented
 * limitation this change doesn't remove.
 */
public class ReleaseFlowWithoutJavaFunctionalTest {

  @TempDir Path tempDir;
  private File repo;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'no-java-fixture'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'duckasteroid-version'\n    id 'duckasteroid-release-flow'\n}\n");
  }

  @Test
  void explainVersionWorksWithNoJavaPluginApplied() throws Exception {
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: init");
    git(repo, "tag", "-a", "v1.0.0", "-m", "v1.0.0");
    git(repo, "commit", "--allow-empty", "-q", "-m", "feat: add a thing");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("explainVersion", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":explainVersion").getOutcome());
    assertTrue(result.getOutput().contains("candidate: v1.1.0"), result.getOutput());
  }

  @Test
  void tagReleaseCandidateWorksWithNoJavaPluginApplied() throws Exception {
    File origin = Files.createTempDirectory("release-flow-without-java-origin").toFile();
    git(origin, "init", "-q", "--bare");
    git(repo, "remote", "add", "origin", origin.getAbsolutePath());
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "feat: first feature");
    git(repo, "push", "-q", "origin", "HEAD:refs/heads/main");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("tagReleaseCandidate", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":tagReleaseCandidate").getOutcome());
    assertTrue(tagExists(repo, "v0.1.0-RC1"));
  }

  @Test
  void installReleaseWorkflowsStillFailsWithoutAJavaToolchainPluginPresent() throws Exception {
    git(repo, "commit", "--allow-empty", "-q", "-m", "chore: init");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("installReleaseWorkflows", "--stacktrace")
            .buildAndFail();

    assertTrue(
        result.getOutput().contains("needs a java-toolchain-configuring plugin"),
        "expected the known java-toolchain gap, got: " + result.getOutput());
  }

  private boolean tagExists(File dir, String tag) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("git", "tag", "-l", tag).directory(dir).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    process.waitFor();
    return !output.trim().isEmpty();
  }

  private void git(File dir, String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
  }
}
