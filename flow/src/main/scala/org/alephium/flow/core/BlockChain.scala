package org.alephium.flow.core

import org.alephium.crypto.Keccak256
import org.alephium.flow.core.BlockChain.ChainDiff
import org.alephium.flow.io.{Disk, IOResult}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {

  def disk: Disk

  def getBlock(hash: Keccak256): IOResult[Block] = {
    disk.getBlock(hash)
  }

  def getBlockUnsafe(hash: Keccak256): Block = {
    disk.getBlockUnsafe(hash)
  }

  def add(block: Block, weight: Int): IOResult[Unit] = {
    add(block, block.parentHash, weight)
  }

  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    assert(!contains(block.hash) && contains(parentHash))
    for {
      _ <- persistBlock(block)
      _ <- add(block.header, parentHash, weight)
    } yield ()
  }

  protected def persistBlock(block: Block): IOResult[Unit] = {
    disk.putBlock(block).right.map(_ => ())
    // TODO: handle transactions later
  }

  protected def persistBlockUnsafe(block: Block): Unit = {
    disk.putBlockUnsafe(block)
    ()
  }

  def calBlockDiffUnsafe(newTip: Keccak256, oldTip: Keccak256): ChainDiff = {
    val hashDiff = calHashDiff(newTip, oldTip)
    ChainDiff(hashDiff.toRemove.map(getBlockUnsafe), hashDiff.toAdd.map(getBlockUnsafe))
  }
}

object BlockChain {
  def fromGenesisUnsafe(genesis: Block)(implicit config: PlatformProfile): BlockChain =
    createUnsafe(genesis, 0, 0)

  private def createUnsafe(rootBlock: Block, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformProfile): BlockChain = {
    val timestamp = rootBlock.header.timestamp
    val rootNode  = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight, timestamp)

    new BlockChain {
      override val disk                                = _config.disk
      override val headerDB                            = _config.headerDB
      override implicit val config: PlatformProfile    = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.persistBlockUnsafe(rootBlock)
      this.addHeaderUnsafe(rootBlock.header)
      this.addNode(rootNode)
    }
  }

  case class ChainDiff(toRemove: AVector[Block], toAdd: AVector[Block])
}
