import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduces (as a regression guard) the real bug this project hit: applying duckasteroid-java to
 * a real git-backed project used to make JGit shell out to the real `git` binary at Gradle
 * *configuration* time (to discover the system-level git config for the ghOwner/ghBranch POM
 * derivation and for VersionResolver), which fails outright under --configuration-cache. See
 * ConfigCacheSafeSystemReader for the fix.
 *
 * Needs a real git repo (not ProjectBuilder's in-memory project, which JavaConventionsPluginTest
 * uses) because the bug only reproduces once duckasteroid-java.gradle's ghOwner/ghBranch
 * try-block actually finds a real .git dir to open - a ProjectBuilder project has none, so it
 * silently falls into the catch-all fallback and never constructs a JGit Repository at all.
 */
public class ConfigCacheFunctionalTest {

  @TempDir Path projectDir;

  @BeforeEach
  void initGitBackedProject() throws IOException, InterruptedException {
    git("init", "-q");
    git("config", "user.email", "test@example.com");
    git("config", "user.name", "Test");
    git("remote", "add", "origin", "https://github.com/duckAsteroid/config-cache-fixture.git");

    Files.writeString(
        projectDir.resolve("settings.gradle"), "rootProject.name = 'config-cache-fixture'\n");
    Files.writeString(projectDir.resolve("build.gradle"), "plugins {\n    id 'duckasteroid-java'\n}\n");

    Path readme = projectDir.resolve("README.md");
    Files.writeString(readme, "fixture project for ConfigCacheFunctionalTest\n");
    git("add", ".");
    git("commit", "-q", "-m", "chore: init");
  }

  @Test
  void applyingDuckasteroidJavaSurvivesConfigurationCache() {
    // GradleRunner.build() throws (failing this test with the real Gradle failure output) if the
    // build doesn't succeed - including the "Configuration cache problems found" failure this
    // reproduces before the fix. A first run stores the cache entry; a second, unmodified run
    // exercises the replay path too (a different code path in Gradle, and the one that matters
    // most in practice once a project has a warm cache).
    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("help", "--configuration-cache")
            .forwardOutput();

    runner.build();
    runner.build();
  }

  private void git(String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process =
        new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
  }
}
