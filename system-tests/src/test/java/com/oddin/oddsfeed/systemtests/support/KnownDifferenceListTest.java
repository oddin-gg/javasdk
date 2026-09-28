package com.oddin.oddsfeed.systemtests.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * KNOWN-DIFFERENCES.md and {@link KnownDifference} say the same thing: every constant has its
 * entry, every entry that names a test has its constant, the tests it names exist, and "to be
 * decided" in the list is what the constant says too.
 */
class KnownDifferenceListTest {

  private static final Pattern HEADING = Pattern.compile("^## (KD-\\d+) .+$");
  private static final Pattern FIELD = Pattern.compile("^- \\*\\*(.+?):\\*\\* (.+)$");
  private static final Pattern TEST = Pattern.compile("`(\\w+)\\.(\\w+)`");
  private static final List<String> FIELDS = List.of("0.0.x", "1.0", "Why", "Pinned by", "Found");
  private static final String SCENARIOS = "com.oddin.oddsfeed.systemtests.";

  @Test
  void everyDifferenceHasItsEntryAndEveryPinnedEntryItsDifference() throws IOException {
    Map<String, Map<String, String>> entries = entries();
    Map<String, KnownDifference> constants = Arrays.stream(KnownDifference.values())
        .collect(Collectors.toMap(KnownDifference::id, difference -> difference));

    List<String> pinned = entries.entrySet().stream()
        .filter(entry -> TEST.matcher(entry.getValue().get("Pinned by")).find())
        .map(Map.Entry::getKey)
        .toList();
    assertThat(constants.keySet()).as("known differences in the code, against the entries that name a test")
        .containsExactlyInAnyOrderElementsOf(pinned);
  }

  @Test
  void everyEntryIsComplete() throws IOException {
    entries().forEach((id, fields) ->
        assertThat(fields.keySet()).as("fields of " + id).containsExactlyElementsOf(FIELDS));
  }

  @Test
  void theListAndTheCodeAgreeOnWhatIsDecided() throws IOException {
    Map<String, Map<String, String>> entries = entries();
    for (KnownDifference difference : KnownDifference.values()) {
      var next = entries.get(difference.id()).get("1.0");
      assertThat(!next.startsWith("To be decided"))
          .as(difference.id() + " decided, in the list (\"" + next + "\") and in the code")
          .isEqualTo(difference.decided());
    }
  }

  @Test
  void theTestsAnEntryNamesExist() throws IOException {
    entries().forEach((id, fields) -> {
      var test = TEST.matcher(fields.get("Pinned by"));
      while (test.find()) {
        var className = test.group(1);
        var method = test.group(2);
        assertThat(testMethod(className, method)).as(id + " is pinned by " + className + "." + method).isTrue();
      }
    });
  }

  private static boolean testMethod(String className, String method) {
    try {
      return Arrays.stream(Class.forName(SCENARIOS + className).getDeclaredMethods())
          .anyMatch(declared -> declared.getName().equals(method)
              && declared.isAnnotationPresent(Test.class));
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** Each entry's fields by name, in the order they appear, per id. */
  private static Map<String, Map<String, String>> entries() throws IOException {
    var basedir = System.getProperty("module.basedir");
    assertThat(basedir).as("module.basedir is passed by the surefire configuration").isNotBlank();
    Map<String, Map<String, String>> entries = new LinkedHashMap<>();
    Map<String, String> current = null;
    String lastField = null;
    List<String> duplicates = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of(basedir, "KNOWN-DIFFERENCES.md"))) {
      var heading = HEADING.matcher(line);
      var field = FIELD.matcher(line);
      if (heading.matches()) {
        current = new LinkedHashMap<>();
        lastField = null;
        if (entries.put(heading.group(1), current) != null) {
          duplicates.add(heading.group(1));
        }
      } else if (line.startsWith("## ")) {
        current = null;
      } else if (current != null && field.matches()) {
        lastField = field.group(1);
        current.put(lastField, field.group(2));
      } else if (current != null && lastField != null && line.startsWith("  ")) {
        // a field wrapped onto the next line
        current.merge(lastField, line.strip(), (value, more) -> value + " " + more);
      }
    }
    assertThat(duplicates).as("ids used twice").isEmpty();
    assertThat(entries).as("entries in KNOWN-DIFFERENCES.md").isNotEmpty();
    return entries;
  }
}
