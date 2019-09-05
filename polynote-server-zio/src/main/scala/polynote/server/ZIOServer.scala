package polynote.server

import java.io.File

import cats.data.Kleisli
import cats.~>
import org.http4s.{HttpApp, HttpRoutes, Request, Response, StaticFile}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, Env}
import polynote.kernel.interpreter, interpreter.Interpreter
import polynote.kernel.{BaseEnv, GlobalEnv}
import zio.blocking.Blocking
import zio.{App, Runtime, Task, TaskR, ZIO}
import zio.interop.catz._
import zio.interop.catz.implicits._

import scala.annotation.tailrec

trait ZIOServer extends App with Http4sDsl[Task] {
  implicit val runtime: Runtime[Environment] = this

  private val blockingEC = unsafeRun(Environment.blocking.blockingExecutor).asEC

  // TODO: obviously, clean this up
  private val indexFile = "/index.html"

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    args     <- ZIO.fromEither(ZIOServer.parseArgs(args)).orDie
    config   <- PolynoteConfig.load[Task](args.configFile).orDie
    port      = config.listen.port
    address   = config.listen.host
    host      = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url       = s"http://$host:$port"
    interps  <- interpreter.Loader.load.orDie
    globalEnv = Env.enrichWith[BaseEnv, GlobalEnv](Environment, GlobalEnv(config, interps))
    manager  <- ZIONotebookManager().provide(globalEnv).orDie
    socketEnv = Env.enrichWith[BaseEnv with GlobalEnv, ZIONotebookManager](globalEnv, manager)
    app      <- httpApp(args.watchUI).provide(socketEnv).orDie
    exit     <- BlazeServerBuilder[Task]
      .bindHttp(port, address)
      .withWebSockets(true)
      .withHttpApp(app)
      .serve.compile.last.orDie
  } yield exit.map(_.code).getOrElse(0)


  private def staticFile(location: String, req: Request[Task]): Task[Response[Task]] =
    Environment.blocking.blockingExecutor.flatMap(ec => StaticFile.fromString[Task](location, ec.asEC, Some(req)).getOrElseF(NotFound().provide(Environment)))

  private def staticResource(path: String, req: Request[Task]): Task[Response[Task]] =
    Environment.blocking.blockingExecutor.flatMap(ec => StaticFile.fromResource(path, ec.asEC, Some(req)).getOrElseF(NotFound().provide(Environment)))

  def serveFile(path: String, req: Request[Task], watchUI: Boolean): Task[Response[Task]] = {
    if (watchUI) {
      val outputLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/$path").toString
      staticFile(outputLoc, req)
    } else {
      staticResource(path, req)
    }
  }

  def downloadFile(path: String, req: Request[Task], config: PolynoteConfig): Task[Response[Task]] = {
    val nbLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"${config.storage.dir}/$path").toString
    staticFile(nbLoc, req)
  }

  object DownloadMatcher extends OptionalQueryParamDecoderMatcher[String]("download")

  def httpApp(watchUI: Boolean): TaskR[BaseEnv with GlobalEnv with ZIONotebookManager, HttpApp[Task]] = for {
    env <- ZIO.access[BaseEnv with GlobalEnv with ZIONotebookManager](identity)
  } yield HttpRoutes.of[Task] {
    case GET -> Root / "ws" => ZIOSocketSession().flatMap(_.toResponse).provide(env)
    case req @ GET -> Root  => serveFile(indexFile, req, watchUI)
    case req @ GET -> "notebook" /: path :? DownloadMatcher(Some("true")) =>
      downloadFile(path.toList.mkString("/"), req, env.polynoteConfig)
    case req @ GET -> "notebook" /: _ => serveFile(indexFile, req, watchUI)
    case req @ GET -> (Root / "polynote-assembly.jar") =>
      StaticFile.fromFile[Task](new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath), blockingEC).getOrElseF(NotFound())
    case req @ GET -> path  =>
      serveFile(path.toString, req, watchUI)
  }.mapF(_.getOrElseF(NotFound()))

}

object ZIOServerApp extends ZIOServer

object ZIOServer {
  case class Args(
    configFile: File = new File("config.yml"),
    watchUI: Boolean = false
  )

  private val serverClass = """polynote.server.(.*)""".r

  @tailrec
  private def parseArgs(args: List[String], current: Args = Args()): Either[Throwable, Args] = args match {
    case Nil => Right(current)
    case ("--config" | "-c") :: filename :: rest => parseArgs(rest, current.copy(configFile = new File(filename)))
    case ("--watch"  | "-w") :: rest => parseArgs(rest, current.copy(watchUI = true))
    case serverClass(_) :: rest => parseArgs(rest, current) // class name might be arg0 in some circumstances
    case other :: rest => Left(new IllegalArgumentException(s"Unknown argument $other"))
  }
}
