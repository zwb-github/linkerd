package io.buoyant.linkerd.protocol.http

import java.util.TimeZone

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.logging._
import com.twitter.util.{Time, TimeFormat}
import io.buoyant.router.RouterLabel

case class AccessLogger(log: Logger, timeFormat: TimeFormat = AccessLogger.DefaultFormat) extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val reqHeaders = req.headerMap
    val remoteHost = req.remoteHost
    val identd = "-"
    val user = "-"
    val referer = reqHeaders.getOrElse("Referer", "-")
    val userAgent = reqHeaders.getOrElse("User-Agent", "-")
    var hostHeader = reqHeaders.getOrElse("Host", "-")
    val reqResource = s"${req.method.toString.toUpperCase} ${req.uri} ${req.version}"

    svc(req).onSuccess { rsp =>
      val statusCode = rsp.statusCode
      val responseBytes = rsp.contentLength.map(_.toString).getOrElse("-")
      val requestEndTime = timeFormat.format(Time.now)
      log.info("""%s %s %s %s [%s] "%s" %d %s "%s" "%s"""", hostHeader, remoteHost, identd, user, requestEndTime,
        reqResource, statusCode, responseBytes, referer, userAgent)
    }
  }
}

object AccessLogger {
  private val DefaultFormat = new TimeFormat("dd/MM/yyyy:HH:mm:ss z", TimeZone.getDefault)

  object param {
    case class File(path: String)
    implicit object File extends Stack.Param[File] {
      val default = File("")
    }

    case class RollPolicy(policy: Policy)
    implicit object RollPolicy extends Stack.Param[RollPolicy] {
      val default = RollPolicy(Policy.Never)
    }

    case class Append(append: Boolean)
    implicit object Append extends Stack.Param[Append] {
      val default = Append(true)
    }

    case class RotateCount(count: Int)
    implicit object RotateCount extends Stack.Param[RotateCount] {
      val default = RotateCount(-1)
    }
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module5[RouterLabel.Param, param.File, param.RollPolicy, param.Append, param.RotateCount, ServiceFactory[Request, Response]] {
      val role = Stack.Role("HttpAccessLogger")
      val description = "Log Http requests/response summaries to a file"
      def make(
        label: RouterLabel.Param,
        file: param.File,
        roll: param.RollPolicy,
        append: param.Append,
        rotate: param.RotateCount,
        factory: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        file match {
          case param.File("") => factory
          case param.File(path) =>
            val logger = LoggerFactory(
              node = "access_" + label,
              level = Some(Level.INFO),
              handlers = List(FileHandler(
                path, roll.policy, append.append, rotate.count,
                // avoid the default prefix
                formatter = new com.twitter.logging.Formatter(prefix = ""),
                level = Some(Level.INFO)
              )),
              useParents = false
            )
            new AccessLogger(logger()).andThen(factory)
        }
    }

}
