import io.javalin.Javalin
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO");
    val logger = LoggerFactory.getLogger("NSCR")
    val app = Javalin.create() { config ->
        config.enableDevLogging()
        config.requestLogger { ctx, ms ->
            logger.info("CTX: ${ctx.method()} ${ctx.fullUrl()}")
        }
    }.start(7000)
    app.get("/") { ctx -> ctx.result("Hello World") }
    app.get("/v2") { ctx ->
        ctx.header("Docker-Distribution-API-Version", "registry/2.0")
        ctx.result("200 OK")
    }
    app.head("/v2/:image/blobs/:hash") { ctx ->
        val image = ctx.pathParam("image")
        val hash = ctx.pathParam("hash")
        logger.info("Want to upload $image $hash")
    }
}