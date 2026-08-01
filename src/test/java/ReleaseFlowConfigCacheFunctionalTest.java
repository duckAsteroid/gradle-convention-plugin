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
 * Reproduces issue #9's second problem: tagReleaseCandidates' doLast closures call the script-level
 * instance methods writeChangelog/createAndPushTag/runGit in duckasteroid-release-flow.gradle,
 * relying on Groovy's owner-chain resolution back to the script instance. Under the configuration
 * cache the closure is serialized and later restored for execution with the task itself as the
 * delegate, so the unqualified call falls through to DefaultTask's invokeMethod and misses -
 * MissingMethodException at execution time.
 *
 * ReleaseFlowSubprojectOnlyFunctionalTest exercises the same aggregator without
 * --configuration-cache, so it doesn't catch this. ReleaseFlowPluginTest only checks task
 * registration via ProjectBuilder, which never runs doLast at all.
 */
public class ReleaseFlowConfigCacheFunctionalTest {

  @TempDir Path tempDir;
  private File repo;
  private File origin;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    origin = Files.createTempDirectory("release-flow-cc-origin").toFile();
    git(origin, "init", "-q", "--bare");

    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'cc-fixture'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'duckasteroid-java'\n    id 'duckasteroid-release-flow'\n}\n");
    Files.createDirectories(tempDir.resolve("src/main/java"));
    Files.writeString(tempDir.resolve("src/main/java/.gitkeep"), "");

    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
    git(repo, "remote", "add", "origin", origin.getAbsolutePath());
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");
    git(repo, "push", "-q", "origin", "HEAD:refs/heads/main");
  }

  @Test
  void tagReleaseCandidatesSurvivesConfigurationCache() throws Exception {
    Files.writeString(tempDir.resolve("src/main/java/Feature.java"), "class Feature {}\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "feat: add a feature");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("tagReleaseCandidates", "--configuration-cache", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":tagReleaseCandidates").getOutcome());
    assertTrue(
        new File(repo, "build/release-manifest.json").exists(),
        "manifest should be written to the root build directory");
    assertTrue(
        gitOutput(repo, "tag", "-l").contains("v0.1.0-RC1"),
        "the RC tag should actually have been created");
  }

  private void git(File dir, String... args) throws IOException, InterruptedException {
    gitOutput(dir, args);
  }

  private String gitOutput(File dir, String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
    return output;
  }
}
