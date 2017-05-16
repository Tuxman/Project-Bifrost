package bifrost.contract

import java.time.Instant

import com.google.common.primitives.Longs
import io.circe._
import io.circe.syntax._
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.encode.Base58

import scala.reflect.runtime.{universe => ru}
import scala.util.{Failure, Success, Try}


class Contract(val Producer: PublicKey25519Proposition,
               val Hub: PublicKey25519Proposition,
               val Investor: PublicKey25519Proposition,
               val storage: JsonObject,
               val agreement: JsonObject,
               val id: Array[Byte]) {

  lazy val json: Json = Map(
    "producer" -> Base58.encode(Producer.pubKeyBytes).asJson,
    "hub" -> Base58.encode(Hub.pubKeyBytes).asJson,
    "investor" -> Base58.encode(Investor.pubKeyBytes).asJson,
    "agreement" -> agreement.asJson,
    "storage" -> storage.asJson
  ).asJson

  def complete(party: PublicKey25519Proposition)(): Try[Contract] = {
    if (Producer.pubKeyBytes sameElements party.pubKeyBytes) {
      Success(this)
    } else if (Hub.pubKeyBytes sameElements party.pubKeyBytes) {
      Success(this)
    } else if (Investor.pubKeyBytes sameElements party.pubKeyBytes) {
      Success(this)
    } else {
      Failure(new IllegalAccessException("Actor doesn't correspond to any"))
    }

  }

  def currentStatus(party: PublicKey25519Proposition)(): Try[Json] = Try {
    storage("status").get
  }

  /**
    * Called by the Producer when the Producer makes delivery of produced goods. This creates an entry in storage to be
    * later endorsed by a Hub.
    *
    * @param party      the public key of the executor of the method call (valid: Producer)
    * @param quantity   the amount of goods supposedly delivered
    * @return
    */
  def deliver(party: PublicKey25519Proposition)(quantity: Long): Try[Contract] = {

    require(party.pubKeyBytes sameElements Producer.pubKeyBytes,
      Failure(new IllegalAccessException(s"[Producer Only]: Account <$party> doesn't have permission to this method."))
    )

    require(quantity > 0L, Failure(new IllegalArgumentException(s"Delivery quantity <$quantity> must be positive")))

    val status: String = storage("status").get.asString.get

    require(!status.equals("expired") && !status.equals("complete"), Failure(new IllegalStateException(s"Cannot deliver while contract status is <$status>")))

    val currentFulfillmentJsonObj: JsonObject = storage("currentFulfillment").getOrElse(
      Map(
        "pendingDeliveries" -> List[Json]().asJson
      ).asJson
    ).asObject.get

    val pendingDeliveriesJson: Json = currentFulfillmentJsonObj("pendingDeliveries").getOrElse(List[Json]().asJson)
    val pdId: String = Base58.encode(
      FastCryptographicHash(
        (pendingDeliveriesJson.asArray.get :+
          Map(
            "quantity" -> quantity.asJson,
            "timestamp" -> Instant.now.toEpochMilli.asJson
          ).asJson
        ).asJson.noSpaces.getBytes
      )
    )

    val newFulfillmentJsonObj: JsonObject = currentFulfillmentJsonObj.add(
      "pendingDeliveries",
      (pendingDeliveriesJson.asArray.get :+
        Map(
          "quantity" -> quantity.asJson,
          "timestamp" -> Instant.now.toEpochMilli.asJson,
          "id" -> pdId.asJson
        ).asJson
      ).asJson
    )

    val newStorage = storage.add("currentFulfillment", newFulfillmentJsonObj.asJson)

    Success(new Contract(Producer, Hub, Investor, newStorage, agreement, id))
  }

  /**
    * Called by the Hub after the Hub takes delivery of goods produced by the Producer. This endorses an existing entry
    * for a pending delivery and updates the total amount of goods delivered by the Producer for this contract.
    *
    * @param party        the public key of the executor of the method call (valid: Hub)
    * @param deliveryId   the id of the pending delivery to endorse
    * @return
    */
  def confirmDelivery(party: PublicKey25519Proposition)(deliveryId: String): Try[Contract] = {

    require(party.pubKeyBytes sameElements Hub.pubKeyBytes,
      Failure(new IllegalAccessException(s"[Hub Only]: Account <$party> doesn't have permission to this method."))
    )

    val currentFulfillmentJsonObj: JsonObject = storage("currentFulfillment").getOrElse(
      Map(
        "pendingDeliveries" -> List[Json]().asJson
      ).asJson
    ).asObject.get

    val pendingDeliveriesJson: Json = currentFulfillmentJsonObj("pendingDeliveries").getOrElse(List[Json]().asJson)
    val pendingDeliveries: Vector[Json] = pendingDeliveriesJson.asArray.get

    val partitionedDeliveries: (Vector[Json], Vector[Json]) = pendingDeliveries.partition(_.asObject.get("id").get.asString.get equals deliveryId)

    require(partitionedDeliveries._1.nonEmpty, Failure(new NoSuchElementException(s"ID <$deliveryId> was not found as a pending delivery.")))

    val oldDeliveredQuantity: Long = currentFulfillmentJsonObj("deliveredQuantity").getOrElse(0L.asJson).asNumber.get.toLong.get

    val newFulfillmentJsonObj: JsonObject = currentFulfillmentJsonObj
      .add("pendingDeliveries", partitionedDeliveries._2.asJson)
      .add("deliveredQuantity", (oldDeliveredQuantity + partitionedDeliveries._1.head.asObject.get("quantity").get.asNumber.get.toLong.get).asJson)

    val newStorage = storage.add("currentFulfillment", newFulfillmentJsonObj.asJson)

    Success(new Contract(Producer, Hub, Investor, newStorage, agreement, id))
  }

  def checkExpiration(party: PublicKey25519Proposition)(): Try[Json] = Try {
    val expiration: Long = this.agreement("expirationTimestamp").get.as[Long].right.get
    (Instant.now().toEpochMilli > expiration).asJson
  }

}

object Contract {

  // get runtime mirror
  val rm: ru.Mirror = ru.runtimeMirror(getClass.getClassLoader)

  // TODO this currently also shows public accessors and the like. Want to restrict. May have to build registry after all
  val contractMethods: Map[String, ru.MethodSymbol] = (ru.typeOf[Contract].decls collect {
    case m: ru.Symbol if m.isMethod => m.asMethod.name.toString -> m.asMethod
  }).toMap

  def apply(cs: Json, id: Array[Byte]): Contract = {
    val jsonMap = cs.asObject.get.toMap

    new Contract(
      new PublicKey25519Proposition(Base58.decode(jsonMap("producer").asString.get).get),
      new PublicKey25519Proposition(Base58.decode(jsonMap("hub").asString.get).get),
      new PublicKey25519Proposition(Base58.decode(jsonMap("investor").asString.get).get),
      jsonMap("storage").asObject.get,
      jsonMap("agreement").asObject.get,
      id
    )
  }

  //noinspection ScalaStyle
  def execute(c: Contract, methodName: String)(party: PublicKey25519Proposition)(args: JsonObject): Try[Either[Contract, Json]] = Try {

    val methodAttempt: Option[ru.MethodSymbol] = contractMethods.get(methodName)

    methodAttempt match {
      case Some(m: ru.MethodSymbol) =>
        val params: List[Any] = m.paramLists.map(p => {
          val typename = p.head.typeSignature.typeSymbol.asClass.name.toString
          p.head.name.toString match {
            case "party" => party
            case _ => typename match {
              case "PublicKey25519Proposition" => PublicKey25519Proposition(Base58.decode(args(p.head.name.toString).get.asString.get).get)
              case "Long" => args(p.head.name.toString).get.asNumber.get.toLong.get
              case "String" => args(p.head.name.toString).get.asString.get
              case i => throw new NotImplementedError(s"Decoder for datatype $i not implemented")
            }
          }
        })

        rm.reflect(c).reflectMethod(m)(params:_*) match {
          case c: Success[Contract] => Left(c.value)
          case j: Success[Json] => Right(j.value)
          case f: Failure[Any] => throw f.exception
        }

      case _ => throw new MatchError(s"Could not find method <$methodName>")
    }
  }

}