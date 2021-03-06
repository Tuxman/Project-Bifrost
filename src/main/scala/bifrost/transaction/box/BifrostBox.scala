package bifrost.transaction.box

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import com.google.common.primitives.{Bytes, Ints, Longs}
import bifrost.scorexMod.GenericBox
import bifrost.transaction.box.proposition.{MofNProposition, MofNPropositionSerializer}
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.serialization.Serializer
import scorex.core.transaction.account.PublicKeyNoncedBox
import scorex.core.transaction.box.proposition.{Constants25519, ProofOfKnowledgeProposition, PublicKey25519Proposition}
import scorex.core.transaction.state.PrivateKey25519
import scorex.crypto.encode.Base58

import scala.util.Try

/**
  * Created by Matthew on 4/11/2017.
  */
abstract class BifrostBox(proposition: ProofOfKnowledgeProposition[PrivateKey25519],
                      val nonce: Long,
                      value: Any) extends GenericBox[ProofOfKnowledgeProposition[PrivateKey25519], Any] {

  override type M = BifrostBox

  override def serializer: Serializer[BifrostBox] = BifrostBoxSerializer

  // lazy val id: Array[Byte] = PublicKeyNoncedBox.idFromBox(proposition, nonce)

  lazy val publicKey = proposition

  val json: Json

  override def equals(obj: Any): Boolean = obj match {
    case acc: BifrostBox => (acc.id sameElements this.id) && acc.value == this.value
    case _ => false
  }


  override def hashCode(): Int = proposition.hashCode()
}


object BifrostBoxSerializer extends Serializer[BifrostBox] {

  override def toBytes(obj: BifrostBox): Array[Byte] = obj match {
    case p: PolyBox => PolyBoxSerializer.toBytes(p)
    case a: ArbitBox => ArbitBoxSerializer.toBytes(a)
    case c: ContractBox => ContractBoxSerializer.toBytes(c)
    case profileb: ProfileBox => ProfileBoxSerializer.toBytes(profileb)
    case _ => throw new Exception("Unanticipated BifrostBox type")
  }

  override def parseBytes(bytes: Array[Byte]): Try[BifrostBox] = {

    val typeLen = Ints.fromByteArray(bytes.take(Ints.BYTES))

    val typeStr: String = new String(bytes.slice(Ints.BYTES, Ints.BYTES + typeLen))

    typeStr match {
      case "ArbitBox" => ArbitBoxSerializer.parseBytes(bytes)
      case "PolyBox" => PolyBoxSerializer.parseBytes(bytes)
      case "ContractBox" => ContractBoxSerializer.parseBytes(bytes)
      case "ProfileBox" => ProfileBoxSerializer.parseBytes(bytes)
      case _ => throw new Exception("Unanticipated Box Type")
    }
  }
}

case class PolyBox(proposition: PublicKey25519Proposition,
                   override val nonce: Long,
                   value: Long) extends BifrostBox(proposition, nonce, value) {
  lazy val id: Array[Byte] = PublicKeyNoncedBox.idFromBox(proposition, nonce)

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "proposition" -> Base58.encode(proposition.pubKeyBytes).asJson,
    "value" -> value.asJson,
    "nonce" -> nonce.asJson
  ).asJson
}

object PolyBoxSerializer extends Serializer[PolyBox] {

  def toBytes(obj: PolyBox): Array[Byte] = {

    val boxType = "PolyBox"

    Ints.toByteArray(boxType.getBytes.length) ++ boxType.getBytes ++ obj.proposition.pubKeyBytes ++ Longs.toByteArray(obj.nonce) ++ Longs.toByteArray(obj.value)
  }

  override def parseBytes(bytes: Array[Byte]): Try[PolyBox] = Try {

    val typeLen = Ints.fromByteArray(bytes.take(Ints.BYTES))

    val typeStr: String = new String(bytes.slice(Ints.BYTES, Ints.BYTES + typeLen))

    val numReadBytes = Ints.BYTES + typeLen

    val pk = PublicKey25519Proposition(bytes.slice(numReadBytes, numReadBytes + Constants25519.PubKeyLength))
    val nonce = Longs.fromByteArray(bytes.slice(numReadBytes + Constants25519.PubKeyLength, numReadBytes + Constants25519.PubKeyLength + Longs.BYTES))

    val curReadBytes = numReadBytes + Constants25519.PubKeyLength + Longs.BYTES

    val value = Longs.fromByteArray(bytes.slice(curReadBytes, curReadBytes + Longs.BYTES))
    PolyBox(pk, nonce, value)
  }

}

case class ArbitBox(proposition: PublicKey25519Proposition,
                   override val nonce: Long,
                   value: Long) extends BifrostBox(proposition, nonce, value) {
  lazy val id: Array[Byte] = PublicKeyNoncedBox.idFromBox(proposition, nonce)

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "proposition" -> Base58.encode(proposition.pubKeyBytes).asJson,
    "value" -> value.asJson,
    "nonce" -> nonce.asJson
  ).asJson
}

object ArbitBoxSerializer extends Serializer[ArbitBox] {

  def toBytes(obj: ArbitBox): Array[Byte] = {

    val boxType = "ArbitBox"

    Bytes.concat(
      Ints.toByteArray(boxType.getBytes.length),
      boxType.getBytes,
      obj.proposition.pubKeyBytes,
      Longs.toByteArray(obj.nonce),
      Longs.toByteArray(obj.value)
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[ArbitBox] = Try {

    val typeLen = Ints.fromByteArray(bytes.take(Ints.BYTES))

    val typeStr: String = new String(bytes.slice(Ints.BYTES, Ints.BYTES + typeLen))

    val numReadBytes = Ints.BYTES + typeLen

    val pk = PublicKey25519Proposition(bytes.slice(numReadBytes, numReadBytes + Constants25519.PubKeyLength))
    val nonce = Longs.fromByteArray(bytes.slice(numReadBytes + Constants25519.PubKeyLength, numReadBytes + Constants25519.PubKeyLength + Longs.BYTES))

    val curReadBytes = numReadBytes + Constants25519.PubKeyLength + Longs.BYTES

    val value = Longs.fromByteArray(bytes.slice(curReadBytes, curReadBytes + Longs.BYTES))
    ArbitBox(pk, nonce, value)
  }

}


case class ContractBox(proposition: MofNProposition,
                       override val nonce: Long,
                       value: Json) extends BifrostBox(proposition, nonce, value) {

  lazy val id: Array[Byte] = FastCryptographicHash(
    MofNPropositionSerializer.toBytes(proposition) ++
    Longs.toByteArray(nonce) ++
    value.noSpaces.getBytes
  )

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "proposition" -> proposition.setOfPubKeyBytes.map(Base58.encode(_).asJson).asJson,
    "value" -> value.asJson,
    "nonce" -> nonce.asJson
  ).asJson

}

object ContractBoxSerializer extends Serializer[ContractBox] {

  def toBytes(obj: ContractBox): Array[Byte] = {

    val boxType = "ContractBox"

    Ints.toByteArray(boxType.getBytes.length) ++
      boxType.getBytes ++
      MofNPropositionSerializer.toBytes(obj.proposition) ++
      Longs.toByteArray(obj.nonce) ++
      Ints.toByteArray(obj.value.noSpaces.getBytes.length) ++
      obj.value.noSpaces.getBytes
  }

  override def parseBytes(bytes: Array[Byte]): Try[ContractBox] = Try {

    val typeLen = Ints.fromByteArray(bytes.take(Ints.BYTES))

    val typeStr: String = new String(bytes.slice(Ints.BYTES, Ints.BYTES + typeLen))

    var numReadBytes = Ints.BYTES + typeLen

    val numOfPk = Ints.fromByteArray(bytes.slice(numReadBytes + Ints.BYTES, numReadBytes + 2*Ints.BYTES))
    val endIndex = numReadBytes + 2*Ints.BYTES + numOfPk*Constants25519.PubKeyLength
    val proposition = MofNPropositionSerializer.parseBytes(bytes.slice(numReadBytes, endIndex)).get
    numReadBytes = endIndex

    val nonce = Longs.fromByteArray(bytes.slice(numReadBytes, numReadBytes + Longs.BYTES))

    numReadBytes += Longs.BYTES

    val valueLen = Ints.fromByteArray(bytes.slice(numReadBytes, numReadBytes + Ints.BYTES))

    val value = parse(new String(bytes.slice(numReadBytes + Ints.BYTES, numReadBytes + Ints.BYTES + valueLen))) match {
      case Left(f) => throw f
      case Right(j: Json) => j
    }

    ContractBox(proposition, nonce, value)
  }

}

/**
  *
  * @param proposition
  * @param nonce: place holder for now. Make it always zero
  * @param value
  * @param key: Name of the profile attribute you wish to use for the box
  */
case class ProfileBox(proposition: PublicKey25519Proposition,
                      override val nonce: Long,
                      value: String,
                      key: String) extends BifrostBox(proposition, nonce, value) {
  lazy val id: Array[Byte] = ProfileBox.idFromBox(proposition, key)

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "proposition" -> Base58.encode(proposition.pubKeyBytes).asJson,
    "value" -> value.asJson,
    "field" -> key.asJson
  ).asJson
}

object ProfileBox {

  val acceptableKeys = Set("role")
  val acceptableRoleValues = Set("investor", "hub", "producer")

  def idFromBox[proposition <: PublicKey25519Proposition](prop: proposition, field: String): Array[Byte] =
    FastCryptographicHash(prop.pubKeyBytes ++ field.getBytes)
}

object ProfileBoxSerializer extends Serializer[ProfileBox] {

  def toBytes(obj: ProfileBox): Array[Byte] = {

    val boxType = "ProfileBox"

    Ints.toByteArray(boxType.getBytes.length) ++ boxType.getBytes ++
      obj.proposition.pubKeyBytes ++
      Ints.toByteArray(obj.value.getBytes.length) ++ obj.value.getBytes ++
      Ints.toByteArray(obj.key.getBytes.length) ++ obj.key.getBytes
  }

  override def parseBytes(bytes: Array[Byte]): Try[ProfileBox] = Try {

    val typeLen = Ints.fromByteArray(bytes.take(Ints.BYTES))

    val typeStr: String = new String(bytes.slice(Ints.BYTES, Ints.BYTES + typeLen))

    var numReadBytes = Ints.BYTES + typeLen

    val pk = PublicKey25519Proposition(bytes.slice(numReadBytes, numReadBytes + Constants25519.PubKeyLength))

    numReadBytes += Constants25519.PubKeyLength

    val valueLen = Ints.fromByteArray(bytes.slice(numReadBytes, numReadBytes + Ints.BYTES))
    val value = new String(bytes.slice(numReadBytes + Ints.BYTES, numReadBytes + Ints.BYTES + valueLen))

    numReadBytes += Ints.BYTES + valueLen
    val fieldLen = Ints.fromByteArray(bytes.slice(numReadBytes, numReadBytes + Ints.BYTES))
    val field = new String(bytes.slice(numReadBytes + Ints.BYTES, numReadBytes + Ints.BYTES + fieldLen))
    ProfileBox(pk, 0L, value, field)
  }

}
