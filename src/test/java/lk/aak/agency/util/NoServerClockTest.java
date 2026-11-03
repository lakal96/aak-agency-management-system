package lk.aak.agency.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server runs on UTC; the agency works on Sri Lanka time. A plain LocalDate.now() quietly gives
 * yesterday's date from midnight to 05:30 there, so all application code must use {@link BusinessTime}.
 */
class NoServerClockTest {

    private static final Pattern FORBIDDEN =
            Pattern.compile("Local(Date|DateTime|Time)\\.now\\(\\)|T\\(java\\.time\\.Local(Date|DateTime|Time)\\)\\)?\\.now\\(\\)");

    @Test
    void applicationCodeNeverAsksTheServerClockForTheDateOrTime() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().matches(".*\\.(java|html|xml)$"))
                    .filter(path -> !path.endsWith("BusinessTime.java"))
                    .filter(path -> contains(path))
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders)
                .as("use BusinessTime.today() / BusinessTime.now() instead, so dates follow Sri Lanka time")
                .isEmpty();
    }

    private static boolean contains(Path path) {
        try {
            return FORBIDDEN.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
