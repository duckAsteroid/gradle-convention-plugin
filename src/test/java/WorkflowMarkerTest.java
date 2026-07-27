import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.conventions.WorkflowMarker;
import org.junit.jupiter.api.Test;

/**
 * Exercises the marker line format itself: rendering, parsing, and the hash-over-body check that
 * installReleaseWorkflows/checkReleaseWorkflows rely on to tell an untouched install from an
 * edited one - see issue #2.
 */
public class WorkflowMarkerTest {

  @Test
  void rendersAndParsesRoundTrip() {
    WorkflowMarker marker = WorkflowMarker.forBody("1.3.0", "name: some-workflow\n");
    WorkflowMarker parsed = WorkflowMarker.parse(marker.render());

    assertEquals(marker.getVersion(), parsed.getVersion());
    assertEquals(marker.getSha256(), parsed.getSha256());
  }

  @Test
  void matchesBodyIsTrueOnlyForTheExactBodyItWasComputedFrom() {
    WorkflowMarker marker = WorkflowMarker.forBody("1.3.0", "name: some-workflow\n");

    assertTrue(marker.matchesBody("name: some-workflow\n"));
    assertTrue(!marker.matchesBody("name: a-different-workflow\n"));
  }

  @Test
  void parseReturnsNullForLinesWithoutTheMarkerPrefix() {
    assertNull(WorkflowMarker.parse("name: Tag and publish a release candidate"));
    assertNull(WorkflowMarker.parse(null));
  }

  @Test
  void parseReturnsNullWhenPrefixPresentButShaMarkerMissing() {
    assertNull(WorkflowMarker.parse(WorkflowMarker.PREFIX + "1.3.0"));
  }

  @Test
  void renderFormatMatchesTheDocumentedMarkerLine() {
    WorkflowMarker marker = new WorkflowMarker("1.3.0", "deadbeef");
    assertEquals("# duckasteroid-workflow-version: 1.3.0 sha256:deadbeef", marker.render());
  }
}
