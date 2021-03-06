package beyond

import akka.actor.Actor
import akka.actor.ActorSelection
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import akka.pattern.pipe
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.util.Timeout
import play.api.libs.concurrent.Akka
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.BodyParser
import play.api.mvc.BodyParsers
import play.api.mvc.Request
import play.api.mvc.Results.InternalServerError
import play.api.mvc.Results.Forbidden
import play.api.mvc.SimpleResult
import play.api.mvc.WrappedRequest
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class RequestWithUsername[A](val username: String, request: Request[A]) extends WrappedRequest[A](request)

object UserAction {
  import play.api.Play.current

  private val userActionActor: ActorSelection = Akka.system.actorSelection("/user/beyondSupervisor/userActionActor")

  def apply(block: => SimpleResult): Action[AnyContent] = apply(_ => block)

  def apply(block: RequestWithUsername[AnyContent] => SimpleResult): Action[AnyContent] = apply(BodyParsers.parse.anyContent)(block)

  def apply[A](bodyParser: BodyParser[A])(block: RequestWithUsername[A] => SimpleResult): Action[A] = async(bodyParser) { request =>
    Future.successful(block(request))
  }

  def async(block: => Future[SimpleResult]): Action[AnyContent] = async(_ => block)

  def async(block: RequestWithUsername[AnyContent] => Future[SimpleResult]): Action[AnyContent] = async(BodyParsers.parse.anyContent)(block)

  def async[A](bodyParser: BodyParser[A])(block: RequestWithUsername[A] => Future[SimpleResult]): Action[A] = new Action[A] {
    override def parser: BodyParser[A] = bodyParser

    override def apply(request: Request[A]): Future[SimpleResult] = {
      // FIXME: Verify if this request belongs to this server.
      request.session.get("username").map { username =>
        import play.api.libs.concurrent.Akka
        import play.api.Play.current
        implicit val ec: ExecutionContext = Akka.system.dispatcher
        implicit val timeout = Timeout(Global.requestTimeout)
        val blockAndRequest = BlockAndRequest(block, new RequestWithUsername(username, request))
        (userActionActor ? blockAndRequest).asInstanceOf[Future[SimpleResult]].recover {
          case _: AskTimeoutException => InternalServerError("UserAction Timeout")
        }
      } getOrElse {
        Future.successful(Forbidden)
      }
    }
  }
}

private case class BlockAndRequest[A] (block: (RequestWithUsername[A]) => Future[SimpleResult], request: RequestWithUsername[A]) extends ConsistentHashable {
  // FIXME: Use a better hashing algorithm.
  override def consistentHashKey: Any = request.username.##
}

private class UserActionActor extends Actor {
  override def receive: Receive = {
    case BlockAndRequest(block, request) => {
      import play.api.libs.concurrent.Akka
      import play.api.Play.current
      implicit val ec: ExecutionContext = Akka.system.dispatcher
      block(request) pipeTo sender
    }
  }
}
