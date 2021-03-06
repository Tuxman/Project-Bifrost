package bifrost.contract

import io.circe.Json
import io.circe.syntax._

import scala.util.Try

class AgreementTerms(val pledge: Long, val xrate: BigDecimal, val share: ShareFunction, val fulfilment: FulfilmentFunction){

  lazy val json: Json = Map(
    "pledge" -> Json.fromLong(pledge),
    "xrate" -> Json.fromString(xrate.toString),
    "share" -> Map(
      "functionType" -> Json.fromString(share.functionType),
      "points" -> Json.arr(share.points.map(p => Json.arr(
          Json.fromDouble(p._1).get,
          Json.arr(Json.fromDouble(p._2._1).get, Json.fromDouble(p._2._2).get, Json.fromDouble(p._2._3).get)
        )
      ):_*)
    ).asJson,
    "fulfilment" -> Map(
      "functionType" -> Json.fromString(fulfilment.functionType),
      "points" -> Json.arr(
        fulfilment.points.map(p => Json.arr(Json.fromLong(p._1), Json.fromDouble(p._2).get)):_*
      )
    ).asJson
  ).asJson

  override def toString: String = s"AgreementTerms(${json.toString})"

}

object AgreementTerms {
  // def validate(terms: AgreementTerms): Try[Unit] = Try {
  //   require(terms.pledge > 0)
  //   require(terms.xrate > 0)
  // }
}