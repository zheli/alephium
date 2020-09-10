package org.alephium.flow.core

import scala.annotation.tailrec

import org.alephium.flow.Utils
import org.alephium.flow.io._
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.{AVector, TimeStamp}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {
  def headerStorage: BlockHeaderStorage

  def getBlockHeader(hash: Hash): IOResult[BlockHeader] = {
    headerStorage.get(hash)
  }

  def getBlockHeaderUnsafe(hash: Hash): BlockHeader = {
    headerStorage.getUnsafe(hash)
  }

  def getParentHash(hash: Hash): IOResult[Hash] = {
    getBlockHeader(hash).map(_.parentHash)
  }

  def getTimestamp(hash: Hash): IOResult[TimeStamp] = {
    getBlockHeader(hash).map(_.timestamp)
  }

  def add(header: BlockHeader, weight: BigInt): IOResult[Unit] = {
    assume(!header.isGenesis)
    val parentHash = header.parentHash
    assume {
      val assertion = for {
        bool1 <- contains(header.hash)
        bool2 <- contains(parentHash)
      } yield !bool1 && bool2
      assertion.getOrElse(false)
    }

    for {
      parentState <- getState(parentHash)
      chainWeight = parentState.chainWeight + header.target
      height      = parentState.height + 1
      _           <- addHeader(header)
      isCanonical <- reorgFor(header, chainWeight, height)
      _ <- addHash(header.hash,
                   parentHash,
                   height,
                   weight,
                   chainWeight,
                   header.timestamp,
                   isCanonical)
    } yield ()
  }

  protected def addGenesis(header: BlockHeader): IOResult[Unit] = {
    assume(header.hash == genesisHash)
    for {
      _ <- addHeader(header)
      _ <- addGenesis(header.hash)
    } yield ()
  }

  def reorgFor(header: BlockHeader, chainWeight: BigInt, height: Int): IOResult[Boolean] = {
    maxChainWeight.map(_ < chainWeight).flatMap {
      case true  => reorgFrom(header.parentHash, height - 1).map(_ => true)
      case false => Right(false)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  final def reorgFrom(hash: Hash, height: Int): IOResult[Unit] = {
    getHashes(height).flatMap { hashes =>
      assume(hashes.contains(hash))
      if (hashes.head == hash) Right(())
      else {
        for {
          _      <- heightIndexStorage.put(height, hash +: hashes.filter(_ != hash))
          parent <- getParentHash(hash)
          _      <- reorgFrom(parent, height - 1)
        } yield ()
      }
    }
  }

  override protected def loadFromStorage(): IOResult[Unit] = {
    super.loadFromStorage()
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerStorage.put(header)
  }

  protected def addHeaderUnsafe(header: BlockHeader): Unit = {
    headerStorage.putUnsafe(header)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]] = {
    getHeight(hash).flatMap {
      case height if height > heightUntil =>
        getBlockHeader(hash).flatMap { header =>
          if (height > heightUntil + 1) chainBack(header.parentHash, heightUntil).map(_ :+ hash)
          else Right(AVector(hash))
        }
      case _ => Right(AVector.empty)
    }
  }

  def getHashTarget(hash: Hash): IOResult[BigInt] = {
    for {
      header    <- getBlockHeader(hash)
      newTarget <- calHashTarget(hash, header.target)
    } yield newTarget
  }

  def getHeightedBlockHeaders(fromTs: TimeStamp,
                              toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] =
    for {
      height <- maxHeight
      result <- searchByTimestampHeight(height, AVector.empty, fromTs, toTs)
    } yield result

  //TODO Make it tailrec
  //TODO Use binary search with the height params to find quicklier all our blocks.
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def searchByTimestampHeight(height: Int,
                                      prev: AVector[(BlockHeader, Int)],
                                      fromTs: TimeStamp,
                                      toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] = {
    getHashes(height).flatMap { hashes =>
      hashes.mapE(getBlockHeader).flatMap { headers =>
        val filteredHeader =
          headers.filter(header => header.timestamp >= fromTs && header.timestamp <= toTs)
        val searchLower = !headers.exists(_.timestamp < fromTs)
        val tmpResult   = prev ++ (filteredHeader.map(_ -> height))

        if (searchLower && height > ALF.GenesisHeight) {
          searchByTimestampHeight(height - 1, tmpResult, fromTs, toTs)
        } else {
          Right(tmpResult)
        }
      }
    }
  }

  def getSyncDataUnsafe(locators: AVector[Hash]): AVector[Hash] = {
    val reversed           = locators.reverse
    val lastCanonicalIndex = reversed.indexWhere(isCanonicalUnsafe)
    if (lastCanonicalIndex == -1) {
      AVector.empty // nothing in common
    } else {
      val lastCanonicalHash = reversed(lastCanonicalIndex)
      val heightFrom        = getHeightUnsafe(lastCanonicalHash) + 1
      val heightTo          = math.min(heightFrom + 1000, maxHeightUnsafe)
      if (heightFrom <= heightTo) getSyncDataUnsafe(heightFrom, heightTo)
      else AVector.empty
    }
  }

  // heightFrom is exclusive, heightTo is inclusive
  def getSyncDataUnsafe(heightFrom: Int, heightTo: Int): AVector[Hash] = {
    @tailrec
    def iter(currentHeader: BlockHeader, currentHeight: Int, acc: AVector[Hash]): AVector[Hash] = {
      if (currentHeight == heightFrom) acc :+ currentHeader.hash
      else {
        val parentHeader = getBlockHeaderUnsafe(currentHeader.parentHash)
        iter(parentHeader, currentHeight - 1, acc :+ currentHeader.hash)
      }
    }

    val startHeader = Utils.unsafe(getHashes(heightTo).map(_.head).flatMap(getBlockHeader))
    iter(startHeader, heightTo, AVector.empty).reverse
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(storages: Storages)(genesisHeader: BlockHeader)(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting): BlockHeaderChain = {
    val initialize = initializeGenesis(genesisHeader)(_)
    createUnsafe(genesisHeader, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(genesisHeader: BlockHeader)(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting): BlockHeaderChain = {
    createUnsafe(genesisHeader, storages, initializeFromStorage)
  }

  def createUnsafe(
      rootHeader: BlockHeader,
      storages: Storages,
      initialize: BlockHeaderChain => IOResult[Unit]
  )(implicit _brokerConfig: BrokerConfig, _consensusSetting: ConsensusSetting): BlockHeaderChain = {
    val headerchain = new BlockHeaderChain {
      override val brokerConfig      = _brokerConfig
      override val consensusConfig   = _consensusSetting
      override val headerStorage     = storages.headerStorage
      override val blockStateStorage = storages.blockStateStorage
      override val heightIndexStorage =
        storages.nodeStateStorage.heightIndexStorage(rootHeader.chainIndex)
      override val chainStateStorage =
        storages.nodeStateStorage.chainStateStorage(rootHeader.chainIndex)
      override val genesisHash = rootHeader.hash
    }

    Utils.unsafe(initialize(headerchain))
    headerchain
  }

  def initializeGenesis(genesisHeader: BlockHeader)(chain: BlockHeaderChain): IOResult[Unit] = {
    chain.addGenesis(genesisHeader)
  }

  def initializeFromStorage(chain: BlockHeaderChain): IOResult[Unit] = {
    chain.loadFromStorage()
  }
}
