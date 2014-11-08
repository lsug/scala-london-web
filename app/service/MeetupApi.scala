package service

import play.api.libs.concurrent.Promise

import scala.concurrent.Future
import play.api.libs.ws._
import play.api.{ Logger, Play }
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.libs.concurrent.Execution.Implicits._
import play.api.cache.Cache
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Generic Meetup API
 *
 * @author Manuel Bernhardt <manuel@bernhardt.io>
 */
trait MeetupApi[A] {

  val envApiKey = scala.util.Properties.envOrElse("MEETUP_API_KEY", "")
  val apiKey = Play.configuration.getString("meetup.apiKey").getOrElse(envApiKey)
  val meetupGrouURL = "london-scala" // Play.configuration.getString("meetup.groupId").getOrElse("london-scala")

  def findAll(entityType: String, processor: JsValue => Seq[A], queryParams: (String, String)*): Future[Seq[A]] = {
    val qs = Seq("group_urlname" -> meetupGrouURL, "key" -> apiKey) ++ queryParams
    val cacheKey = entityType + qs.mkString

    Cache.getAs[Future[Seq[A]]](cacheKey) match {
      case Some(c) => c
      case None =>
        val eventuallyMeetupResponse = WS.url(s"https://api.meetup.com/2/$entityType")
          .withQueryString(qs: _*)
          .get()
          .map { result =>
            if (result.status == 200) {
              val res = processor(result.json)
              Cache.set(cacheKey, res, expiration = 5 minutes)
              res
            } else {
              Logger.warn("Could not retrieve events from meetup. Did you configure the apiKey in the application configuration or the MEETUP_API_KEY environment variable?")
              Seq.empty
            }
          }

        val eventuallyTimeout = Promise.timeout("Meetup timeout", 3.seconds)
        Future.firstCompletedOf(Seq(eventuallyMeetupResponse, eventuallyTimeout)).map {
          case r: Seq[A] => r
          case timeout: String => Seq.empty
        }
    }

  }

}
