import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.duckasteroid.conventions.CommitAnalyzer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class CommitAnalyzerTest {

  @ParameterizedTest
  @CsvSource({
      "fix: correct off-by-one error, PATCH",
      "perf: speed up parsing, PATCH",
      "feat: add support for widgets, MINOR",
      "feat(api): add support for widgets, MINOR",
      "feat!: drop support for old config format, MAJOR",
      "feat(api)!: drop support for old config format, MAJOR",
      "chore: bump dependency versions, NONE",
      "docs: fix typo in README, NONE",
      "Merge pull request #1 from foo/bar, NONE",
      "'', NONE",
  })
  void classifiesSingleCommitSubjectLine(String message, CommitAnalyzer.Bump expected) {
    assertEquals(expected, CommitAnalyzer.analyzeOne(message));
  }

  @Test
  void detectsBreakingChangeFooterRegardlessOfType() {
    String message = "fix: patch a bug\n\nBREAKING CHANGE: removes the old API entirely";
    assertEquals(CommitAnalyzer.Bump.MAJOR, CommitAnalyzer.analyzeOne(message));
  }

  @Test
  void nullMessageIsNone() {
    assertEquals(CommitAnalyzer.Bump.NONE, CommitAnalyzer.analyzeOne(null));
  }

  @Test
  void batchTakesHighestSeverityAcrossCommits() {
    List<String> messages = Arrays.asList(
        "chore: tidy up",
        "fix: correct a bug",
        "feat: add a thing",
        "docs: update readme"
    );
    assertEquals(CommitAnalyzer.Bump.MINOR, CommitAnalyzer.analyze(messages));
  }

  @Test
  void batchWithBreakingChangeIsMajorEvenIfMostCommitsAreMinor() {
    List<String> messages = Arrays.asList(
        "feat: add a thing",
        "feat!: remove the old thing"
    );
    assertEquals(CommitAnalyzer.Bump.MAJOR, CommitAnalyzer.analyze(messages));
  }

  @Test
  void emptyBatchIsNone() {
    assertEquals(CommitAnalyzer.Bump.NONE, CommitAnalyzer.analyze(List.of()));
  }
}
