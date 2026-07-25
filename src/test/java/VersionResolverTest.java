import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.conventions.VersionResolver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises VersionResolver against a real throwaway git repo rather than mocking git - the whole
 * point of this class is git plumbing, so a fixture repo is the honest way to test it.
 */
public class VersionResolverTest {

  private static final String PREFIX = "v";

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
  void lastFinalVersionIsZeroWhenNoTagsExist() throws Exception {
    commit("feat: first commit");
    assertEquals("0.0.0", VersionResolver.lastFinalVersion(repo, PREFIX));
  }

  @Test
  void lastFinalVersionIgnoresReleaseCandidateTags() throws Exception {
    commit("feat: first commit");
    tag("v1.0.0");
    commit("feat: second feature");
    tag("v1.1.0-RC1");
    assertEquals("1.0.0", VersionResolver.lastFinalVersion(repo, PREFIX));
  }

  @Test
  void candidateVersionBumpsMinorForFeat() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals("1.1.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));
  }

  @Test
  void candidateVersionTakesHighestBumpAcrossCommitsSinceLastRelease() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("fix: patch a bug");
    commit("feat!: breaking change");
    assertEquals("2.0.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));
  }

  @Test
  void candidateVersionUnchangedWhenNoQualifyingCommits() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("docs: update readme");
    assertEquals("1.0.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));
  }

  @Test
  void buildVersionReturnsExactTagVerbatimWhenHeadIsOnOne() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    assertEquals("v1.0.0", VersionResolver.resolveBuildVersion(repo, PREFIX, "main"));
  }

  @Test
  void buildVersionAppendsSnapshotWhenNotExactlyOnATag() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals("1.1.0-SNAPSHOT", VersionResolver.resolveBuildVersion(repo, PREFIX, "release"));
  }

  @Test
  void buildVersionEmbedsSanitizedBranchNameForFeatureBranches() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("fix: patch a bug");
    assertEquals(
        "1.0.1-feature-cool-stuff-SNAPSHOT",
        VersionResolver.resolveBuildVersion(repo, PREFIX, "feature/cool-stuff"));
  }

  @Test
  void nextReleaseCandidateTagStartsAtOneThenIncrements() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals("v1.1.0-RC1", VersionResolver.nextReleaseCandidateTag(repo, PREFIX, "1.1.0"));
    tag("v1.1.0-RC1");
    assertEquals("v1.1.0-RC2", VersionResolver.nextReleaseCandidateTag(repo, PREFIX, "1.1.0"));
  }

  @Test
  void promoteTagStripsRcSuffixFromNearestReachableRcTag() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    tag("v1.1.0-RC1");
    commit("fix: small follow-up");
    tag("v1.1.0-RC2");
    assertEquals("v1.1.0", VersionResolver.promoteTag(repo, PREFIX));
  }

  @Test
  void promoteTagThrowsWhenNoRcTagIsReachable() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    Exception ex =
        assertThrows(IllegalStateException.class, () -> VersionResolver.promoteTag(repo, PREFIX));
    assertTrue(ex.getMessage().contains("nothing to promote"));
  }

  @Test
  void lastFinalVersionFallsBackToRootPrefixWhenModuleHasNoTagYet() throws Exception {
    commit("chore: init");
    tag("v2.0.0");
    // "newmod/v" has no tag of its own - should inherit the root "v" line via fallback.
    assertEquals("2.0.0", VersionResolver.lastFinalVersion(repo, "newmod/v", List.of("v")));
    assertEquals("0.0.0", VersionResolver.lastFinalVersion(repo, "newmod/v"));
  }

  @Test
  void candidateVersionUsesFallbackPrefixVersionAsBase() throws Exception {
    commit("chore: init");
    tag("v2.0.0");
    commit("feat: new module's first feature");
    assertEquals(
        "2.1.0", VersionResolver.resolveCandidateVersion(repo, "newmod/v", "", List.of("v")));
  }

  @Test
  void candidateVersionOnlyCountsCommitsTouchingModulePath() throws Exception {
    commitFile("root.txt", "init", "chore: init");
    tag("v1.0.0");
    commitFile("moduleA/file.txt", "a", "fix: bug in module A");
    commitFile("moduleB/file.txt", "b", "feat: big feature in module B");

    assertEquals("1.0.1", VersionResolver.resolveCandidateVersion(repo, PREFIX, "moduleA", List.of()));
    assertEquals("1.1.0", VersionResolver.resolveCandidateVersion(repo, PREFIX, "moduleB", List.of()));
    // Unscoped (root, no modulePath): highest bump across ALL commits wins, as before.
    assertEquals("1.1.0", VersionResolver.resolveCandidateVersion(repo, PREFIX, "", List.of()));
  }

  private void commit(String message) throws IOException, InterruptedException {
    git("commit", "--allow-empty", "-m", message);
  }

  private void commitFile(String relativePath, String content, String message)
      throws IOException, InterruptedException {
    Path file = tempDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    git("add", relativePath);
    git("commit", "-m", message);
  }

  private void tag(String name) throws IOException, InterruptedException {
    git("tag", "-a", name, "-m", name);
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
