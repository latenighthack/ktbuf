import com.latenighthack.ktbuf.test.server.runTestWithServer
import io.ktor.server.application.*
import kotlin.test.Test

fun Application.attachTestServices() {
}

class ServerTests {
    @Test
    fun testServerRuns() = runTestWithServer(Application::attachTestServices) {
    }
}
