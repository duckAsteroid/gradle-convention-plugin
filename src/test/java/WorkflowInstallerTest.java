import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.conventions.WorkflowInstaller;
import io.github.duckasteroid.conventions.WorkflowInstaller.Result;
import io.github.duckasteroid.conventions.WorkflowMarker;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the safe-install skip/overwrite rules from issue #2 against real files - no file
 * present, foreign file, untouched-since-install, edited-since-install (with and without force).
 */
public class WorkflowInstallerTest {

  @TempDir Path tempDir;

  @Test
  void installsWhenNoFileIsPresent() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.INSTALLED, result);
    assertTrue(Files.readString(target.toPath()).startsWith(WorkflowMarker.PREFIX + "1.3.0 sha256:"));
    assertTrue(Files.readString(target.toPath()).endsWith("name: workflow\n"));
  }

  @Test
  void createsMissingParentDirectories() throws IOException {
    File target = tempDir.resolve("nested/dir/release-candidate.yml").toFile();

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.INSTALLED, result);
    assertTrue(target.exists());
  }

  @Test
  void skipsAFileWithNoMarkerRatherThanOverwritingIt() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    Files.writeString(target.toPath(), "name: someone elses hand-written workflow\n");

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.SKIPPED_FOREIGN, result);
    assertEquals("name: someone elses hand-written workflow\n", Files.readString(target.toPath()));
  }

  @Test
  void overwritesAFileThatIsUnmodifiedSinceANOlderInstall() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.2.0", "name: workflow\n", false);

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow v2\n", false);

    assertEquals(Result.OVERWRITTEN, result);
    assertTrue(Files.readString(target.toPath()).endsWith("name: workflow v2\n"));
  }

  @Test
  void reInstallingTheSameVersionAndBodyIsUpToDate() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.UP_TO_DATE, result);
  }

  @Test
  void skipsAFileEditedSinceInstallRatherThanClobberingTheEdit() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.2.0", "name: workflow\n", false);
    Files.writeString(target.toPath(), Files.readString(target.toPath()) + "# a hand-added step\n");

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow v2\n", false);

    assertEquals(Result.SKIPPED_MODIFIED, result);
    assertTrue(Files.readString(target.toPath()).contains("# a hand-added step"));
  }

  @Test
  void forceOverwritesAFileEditedSinceInstall() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.2.0", "name: workflow\n", false);
    Files.writeString(target.toPath(), Files.readString(target.toPath()) + "# a hand-added step\n");

    Result result = WorkflowInstaller.install(target, "1.3.0", "name: workflow v2\n", true);

    assertEquals(Result.FORCED, result);
    assertTrue(Files.readString(target.toPath()).endsWith("name: workflow v2\n"));
  }
}
