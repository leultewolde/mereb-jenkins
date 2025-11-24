import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertTrue

class SandboxGuardTest {

    @Test
    void "rejects direct filesystem access in pipeline sources"() {
        List<Path> roots = ['src', 'vars']
                .collect { Paths.get(it) }
                .findAll { Files.exists(it) }

        List<String> offenders = []

        roots.each { Path root ->
            Files.walk(root).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.groovy') }
                        .forEach { Path file ->
                            String content = Files.readString(file)
                            [
                                    ~/new\s+File\s*\(/,
                                    ~/java\.io\.File/,
                                    ~/new\s+URL\s*\(/,
                                    ~/HttpURLConnection/,
                                    ~/openConnection\s*\(/
                            ].each { pattern ->
                                def matcher = content =~ pattern
                                if (matcher.find()) {
                                    offenders << "${file}:${matcher.group()}"
                                }
                            }
                        }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Sandbox-banned filesystem access found. Replace direct File usage with pipeline steps: ${offenders.join(', ')}")
    }
}
