package bifrost.state

import java.io.File
import java.time.Instant

import com.google.common.primitives.Longs
import bifrost.blocks.BifrostBlock
import bifrost.scorexMod.{GenericBoxMinimalState, GenericStateChanges}
import bifrost.transaction._
import bifrost.transaction.box._
import bifrost.transaction.box.proposition.MofNProposition
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.settings.Settings
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.{ProofOfKnowledgeProposition, Proposition, PublicKey25519Proposition}
import scorex.core.transaction.state.MinimalState.VersionTag
import scorex.core.transaction.state.{PrivateKey25519, StateChanges}
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58

import scala.util.{Failure, Success, Try}

case class BifrostTransactionChanges(toRemove: Set[BifrostBox], toAppend: Set[BifrostBox], minerReward: Long)

case class BifrostStateChanges(override val boxIdsToRemove: Set[Array[Byte]],
                               override val toAppend: Set[BifrostBox], timestamp: Long)
  extends GenericStateChanges[Any, ProofOfKnowledgeProposition[PrivateKey25519], BifrostBox](boxIdsToRemove, toAppend)

/**
  * BifrostState is a data structure which deterministically defines whether an arbitrary transaction is valid and so
  * applicable to it or not. Also has methods to get a closed box, to apply a persistent modifier, and to roll back
  * to a previous version.
  * @param storage: singleton Iodb storage instance
  * @param version: blockId used to identify each block. Also used for rollback
  * @param timestamp: timestamp of the block that results in this state
  */
case class BifrostState(storage: LSMStore, override val version: VersionTag, timestamp: Long)
  extends GenericBoxMinimalState[Any, ProofOfKnowledgeProposition[PrivateKey25519],
    BifrostBox, BifrostTransaction, BifrostBlock, BifrostState] with ScorexLogging {

  override type NVCT = BifrostState
  type P = BifrostState.P
  type T = BifrostState.T
  type TX = BifrostState.TX
  type BX = BifrostState.BX
  type BPMOD = BifrostState.BPMOD
  type GSC = BifrostState.GSC
  type BSC = BifrostState.BSC

  override def semanticValidity(tx: BifrostTransaction): Try[Unit] = BifrostState.semanticValidity(tx)

  private def lastVersionString = storage.lastVersionID.map(v => Base58.encode(v.data)).getOrElse("None")

  private def getProfileBox(prop: PublicKey25519Proposition, field: String): Try[ProfileBox] = {
    val boxBytes = storage.get(ByteArrayWrapper(ProfileBox.idFromBox(prop, field)))
    ProfileBoxSerializer.parseBytes(boxBytes.get.data)
  }

  override def closedBox(boxId: Array[Byte]): Option[BX] =
    storage.get(ByteArrayWrapper(boxId))
      .map(_.data)
      .map(BifrostBoxSerializer.parseBytes)
      .flatMap(_.toOption)

  override def rollbackTo(version: VersionTag): Try[NVCT] = Try {
    if (storage.lastVersionID.exists(_.data sameElements version)) {
      this
    } else {
      log.debug(s"Rollback BifrostState to ${Base58.encode(version)} from version $lastVersionString")
      storage.rollback(ByteArrayWrapper(version))
      val timestamp: Long = Longs.fromByteArray(storage.get(ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes))).get.data)
      BifrostState(storage, version, timestamp)
    }
  }

  override def changes(mod: BPMOD): Try[GSC] = BifrostState.changes(mod)

  override def applyChanges(changes: GSC, newVersion: VersionTag): Try[NVCT] = Try {

    println(s"${changes.boxIdsToRemove.map(Base58.encode)}")
    val boxIdsToRemove = changes.boxIdsToRemove.map(ByteArrayWrapper.apply)

    // TODO check if b.bytes screws up compared to BifrostBoxCompanion.toBytes
    val boxesToAdd = changes.toAppend.map(b => ByteArrayWrapper(b.id) -> ByteArrayWrapper(b.bytes))

    log.debug(s"Update BifrostState from version $lastVersionString to version ${Base58.encode(newVersion)}. " +
      s"Removing boxes with ids ${boxIdsToRemove.map(b => Base58.encode(b.data))}, " +
      s"adding boxes ${boxesToAdd.map(b => Base58.encode(b._1.data))}")

    val timestamp: Long = changes.asInstanceOf[BifrostStateChanges].timestamp

    boxIdsToRemove.foreach(tr => println(s"${Console.RED}Trying to remove ${Base58.encode(tr.data)}${Console.RESET}"))
    if (storage.lastVersionID.isDefined) boxIdsToRemove.foreach(i => require(closedBox(i.data).isDefined))

    storage.update(
      ByteArrayWrapper(newVersion),
      boxIdsToRemove,
      boxesToAdd + (ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes)) -> ByteArrayWrapper(Longs.toByteArray(timestamp)))
    )

    val newSt = BifrostState(storage, newVersion, timestamp)
    boxIdsToRemove.foreach(box => require(newSt.closedBox(box.data).isEmpty, s"Box $box is still in state"))
    newSt

  }

  override def validate(transaction: TX): Try[Unit] = transaction match {
    case poT: PolyTransfer => validatePolyTransfer(poT)
    case cc: ContractCreation => validateContractCreation(cc)
    case prT: ProfileTransaction => validateProfileTransaction(prT)
    case cme: ContractMethodExecution => validateContractMethodExecution(cme)
  }

  /**
    *
    * @param poT: the PolyTransfer to validate
    * @return
    */
  def validatePolyTransfer(poT: PolyTransfer): Try[Unit] = Try {

    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        poT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: PolyBox) =>
                if (unlocker.boxKey.isValid(box.proposition, poT.messageToSign)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception("Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )

        )
      }

      boxesSumTry flatMap { openSum =>
        if (poT.newBoxes.map {
          case p: PolyBox => p.value
          case _ => 0L
        }.sum == openSum - poT.fee) {
          Success[Unit](Unit)
        } else {
          Failure(new Exception("Negative fee"))
        }
      }

    }

    statefulValid.flatMap(_ => semanticValidity(poT))
  }

  /**
    * validates ContractCreation instance on its unlockers && timestamp of the contract
    *
    * @param cc: ContractCreation object
    * @return
    */
  //noinspection ScalaStyle
  def validateContractCreation(cc: ContractCreation): Try[Unit] = Try {

    /* First check to see all roles are present */
    val roleBoxes: IndexedSeq[ProfileBox] = cc.signatures.zipWithIndex.map {
      case (sig, index) =>
        require(sig.isValid(cc.parties(index)._2, cc.messageToSign))
        getProfileBox(cc.parties(index)._2, "role").get
    }

    require(ProfileBox.acceptableRoleValues.equals(roleBoxes.map(_.value).toSet))

    /* Verifies that the role boxes match the roles stated in the contract creation */
    require(roleBoxes.zip(cc.parties.map(_._1)).forall { case (box, role) => box.value.equals(role.toString) })

    val unlockersValid: Try[Unit] = cc.unlockers.foldLeft[Try[Unit]](Success())((unlockersValid, unlocker) =>

      unlockersValid.flatMap { (unlockerValidity) =>
        closedBox(unlocker.closedBoxId) match {
          case Some(box) =>
            if (unlocker.boxKey.isValid(box.proposition, cc.messageToSign)) {
              Success()
            } else {
              Failure(new Exception("Incorrect unlcoker"))
            }
          case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
        }
      }
    )

    val statefulValid = unlockersValid flatMap { _ =>

      val boxesAreNew = cc.newBoxes.forall(curBox => storage.get(ByteArrayWrapper(curBox.asInstanceOf[ContractBox].id)) match {
        case Some(box) => false
        case None => true
      })

      val txTimestampIsAcceptable = cc.timestamp > timestamp && timestamp < Instant.now().toEpochMilli


      if (boxesAreNew && txTimestampIsAcceptable) {
        Success[Unit](Unit)
      } else {
        Failure(new Exception("Boxes attempt to overwrite existing contract"))
      }
    }

    statefulValid.flatMap(_ => semanticValidity(cc))

    // TODO check reputation of parties
  }

  /**
    *
    * @param pt: ProfileTransaction
    * @return success or failure
    */
  def validateProfileTransaction(pt: ProfileTransaction): Try[Unit] = Try {
    /* Make sure there are no existing boxes of the all fields in tx
    *  If there is one box that exists then the tx is invalid
    * */
    require(pt.newBoxes.forall(curBox => getProfileBox(pt.from, curBox.asInstanceOf[ProfileBox].key) match {
      case Success(box) => false
      case Failure(box) => true
    }))

    semanticValidity(pt)
  }

  /**
    *
    * @param cme: the ContractMethodExecution to validate
    * @return
    */
  def validateContractMethodExecution(cme: ContractMethodExecution): Try[Unit] = Try {

    val contractBytes = storage.get(ByteArrayWrapper(cme.contractBox.id))

    //TODO fee verification

    /* Contract exists */
    if(contractBytes.isEmpty) {
      Failure(new NoSuchElementException(s"Contract ${cme.contractBox.id} does not exist"))

    } else {

      val contractProposition: MofNProposition = ContractBoxSerializer.parseBytes(contractBytes.get.data).get.proposition
      val profileBox = getProfileBox(cme.party._2, "role").get

      /* This person belongs to contract */
      if (!cme.signatures(0).isValid(contractProposition, cme.messageToSign)) {
        Failure(new IllegalAccessException(s"Signature is invalid for contractBox"))

      /* Signature matches profilebox owner */
      } else if (!cme.signatures(1).isValid(profileBox.proposition, cme.messageToSign)) {
        Failure(new IllegalAccessException(s"Signature is invalid for ${Base58.encode(cme.party._2.pubKeyBytes)} profileBox"))

      /* Role provided by CME matches profilebox */
      } else if (!profileBox.value.equals(cme.party._1.toString)) {
        Failure(new IllegalAccessException(s"Role ${cme.party._1} for ${Base58.encode(cme.party._2.pubKeyBytes)} does not match ${profileBox.value} in profileBox"))

      /* Timestamp is after most recent block, not in future */
      } else if (cme.timestamp <= timestamp || timestamp >= Instant.now().toEpochMilli) {
        Failure(new Exception("Unacceptable timestamp"))

      } else {
        semanticValidity(cme)
      }

    }
  }
}

object BifrostState {

  type T = Any
  type TX = BifrostTransaction
  type P = ProofOfKnowledgeProposition[PrivateKey25519]
  type BX = BifrostBox
  type BPMOD = BifrostBlock
  type GSC = GenericStateChanges[T, P, BX]
  type BSC = BifrostStateChanges

  def semanticValidity(tx: TX): Try[Unit] = {
    tx match {
      case poT: PolyTransfer => PolyTransfer.validate(poT)
      case cc: ContractCreation => ContractCreation.validate(cc)
      case prT: ProfileTransaction => ProfileTransaction.validate(prT)
      case cme: ContractMethodExecution => ContractMethodExecution.validate(cme)
      case _ => Failure(new Exception("Semantic validity not implemented for " + tx.getClass.toGenericString))
    }
  }


  def changes(mod: BPMOD): Try[GSC] = {
    Try {
      val initial = (Set(): Set[Array[Byte]], Set(): Set[BX], 0L)

      val boxDeltas: Seq[(Set[Array[Byte]], Set[BX], Long)] = mod.transactions match {
        case Some(txSeq) => txSeq.map {
          // (rm, add, fee)
          case sc: PolyTransfer => (sc.boxIdsToOpen.toSet, sc.newBoxes.toSet, sc.fee)
          case cc: ContractCreation => (cc.boxIdsToOpen.toSet, cc.newBoxes.toSet, cc.fee)
          case pt: ProfileTransaction => (pt.boxIdsToOpen.toSet, pt.newBoxes.toSet, pt.fee)
        }
      }

      val (toRemove: Set[Array[Byte]], toAdd: Set[BX], reward: Long) =
        boxDeltas.foldLeft((Set[Array[Byte]](), Set[BX](), 0L))((aggregate, boxDelta) => {
          (aggregate._1 ++ boxDelta._1, aggregate._2 ++ boxDelta._2, aggregate._3 + boxDelta._3 )
        })

      //no reward additional to tx fees
      BifrostStateChanges(toRemove, toAdd, mod.timestamp)
    }
  }

  def readOrGenerate(settings: Settings, callFromGenesis: Boolean = false): BifrostState = {
    val dataDirOpt = settings.dataDirOpt.ensuring(_.isDefined, "data dir must be specified")
    val dataDir = dataDirOpt.get

    new File(dataDir).mkdirs()

    val iFile = new File(s"$dataDir/state")
    iFile.mkdirs()
    val stateStorage = new LSMStore(iFile)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        stateStorage.close()
      }
    })
    val version = stateStorage.lastVersionID.map(_.data).getOrElse(Array.emptyByteArray)

    var timestamp: Long = 0L
    if (callFromGenesis) {
      timestamp = System.currentTimeMillis()
    } else {
      timestamp = Longs.fromByteArray(stateStorage.get(ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes))).get.data)
    }

    BifrostState(stateStorage, version, timestamp)
  }

  def genesisState(settings: Settings, initialBlocks: Seq[BPMOD]): BifrostState = {
    initialBlocks.foldLeft(readOrGenerate(settings, callFromGenesis = true)) { (state, mod) =>
      state.changes(mod).flatMap(cs => state.applyChanges(cs, mod.id)).get
    }
  }
}