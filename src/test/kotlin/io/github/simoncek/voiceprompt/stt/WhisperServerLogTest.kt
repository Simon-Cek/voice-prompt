package io.github.simoncek.voiceprompt.stt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Review N11: the server log lives in the home that is shared across IDEs, so its name must be
 * per-process — two IDEs must not truncate each other's log (on Windows the concurrent writes
 * can even be a sharing violation).
 */
class WhisperServerLogTest {

    private lateinit var home: Path

    @Before
    fun setUp() {
        home = Files.createTempDirectory("vp-log-home")
        VoicePromptPaths.override = home
    }

    @After
    fun tearDown() {
        VoicePromptPaths.override = null
        home.toFile().deleteRecursively()
    }

    @Test
    fun `the default log file is under the shared home and named per process`() {
        val log = WhisperServerEngine.defaultLogFile()
        val pid = ProcessHandle.current().pid().toString()

        assertEquals("the log stays next to the runtime in the shared home", home, log.parentFile.toPath())
        assertTrue(
            "the per-process name keeps two IDEs from clobbering one log: ${log.name}",
            log.name == "whisper-server-$pid.log",
        )
    }
}
