import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.duckasteroid.conventions.WorkflowChecker;
import io.github.duckasteroid.conventions.WorkflowChecker.CheckResult;
import io.github.duckasteroid.conventions.WorkflowChecker.Status;
import io.github.duckasteroid.conventions.WorkflowInstaller;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises checkReleaseWorkflows' read-only status classification (missing/not-ours/tampered/
 * stale/up-to-date) - see issue #2. Never mutates the file under test.
 */
public class WorkflowCheckerTest {

  @TempDir Path tempDir;

  @Test
  void missingWhenTheFileDoesNotExist() {
    File target = tempDir.resolve("release-candidate.yml").toFile();

    CheckResult result = WorkflowChecker.check(target, "1.3.0");

    assertEquals(Status.MISSING, result.getStatus());
    assertNull(result.getInstalledVersion());
  }

  @Test
  void notOursWhenTheFileHasNoMarker() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    Files.writeString(target.toPath(), "name: someone elses hand-written workflow\n");

    CheckResult result = WorkflowChecker.check(target, "1.3.0");

    assertEquals(Status.NOT_OURS, result.getStatus());
  }

  @Test
  void upToDateWhenMarkerHashMatchesAndVersionMatchesCurrent() {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);

    CheckResult result = WorkflowChecker.check(target, "1.3.0");

    assertEquals(Status.UP_TO_DATE, result.getStatus());
    assertEquals("1.3.0", result.getInstalledVersion());
  }

  @Test
  void staleWhenMarkerHashMatchesButVersionIsOlderThanCurrent() {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.2.0", "name: workflow\n", false);

    CheckResult result = WorkflowChecker.check(target, "1.3.0");

    assertEquals(Status.STALE, result.getStatus());
    assertEquals("1.2.0", result.getInstalledVersion());
  }

  @Test
  void tamperedWhenBodyNoLongerMatchesTheMarkerHash() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    WorkflowInstaller.install(target, "1.3.0", "name: workflow\n", false);
    Files.writeString(target.toPath(), Files.readString(target.toPath()) + "# a hand-added step\n");

    CheckResult result = WorkflowChecker.check(target, "1.3.0");

    assertEquals(Status.TAMPERED, result.getStatus());
    assertEquals("1.3.0", result.getInstalledVersion());
  }
}
