package org.alephium.flow.core

import org.scalatest.BeforeAndAfter
import org.scalatest.EitherValues._

import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.io.IOError
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.model.{Block, NoIndexModelGenerators}
import org.alephium.util.{AlephiumSpec, AVector}

class BlockChainSpec extends AlephiumSpec with BeforeAndAfter with NoIndexModelGenerators {
  trait Fixture extends AlephiumConfigFixture {
    val genesis  = Block.genesis(AVector.empty, consensusConfig.maxMiningTarget, 0)
    val blockGen = blockGenOf(AVector.fill(brokerConfig.depsNum)(genesis.hash))
    val chainGen = chainGenOf(4, genesis)

    def buildBlockChain(genesisBlock: Block = genesis): BlockChain = {
      val storages = StoragesFixture.buildStorages(rootPath)
      BlockChain.createUnsafe(genesisBlock, storages, BlockChain.initializeGenesis(genesisBlock)(_))
    }

    def createBlockChain(blocks: AVector[Block]): BlockChain = {
      assume(blocks.nonEmpty)
      val chain = buildBlockChain(blocks.head)
      blocks.toIterable.zipWithIndex.tail foreach {
        case (block, index) =>
          chain.add(block, index * 2)
      }
      chain
    }

    def addBlocks(chain: BlockChain, blocks: AVector[Block]): Unit = {
      blocks.foreachWithIndex((block, index) => chain.add(block, index + 1).isRight is true)
    }
  }

  it should "initialize genesis correctly" in new Fixture {
    val chain = buildBlockChain()
    chain.contains(genesis) isE true
    chain.getHeight(genesis.hash) isE ALF.GenesisHeight
    chain.getWeight(genesis.hash) isE ALF.GenesisWeight
    chain.getChainWeight(genesis.hash) isE ALF.GenesisWeight
    chain.containsUnsafe(genesis.hash) is true
    chain.getHeightUnsafe(genesis.hash) is ALF.GenesisHeight
    chain.getWeightUnsafe(genesis.hash) is ALF.GenesisWeight
    chain.getChainWeightUnsafe(genesis.hash) is ALF.GenesisWeight
    chain.getTimestamp(genesis.hash) isE ALF.GenesisTimestamp
  }

  it should "add block correctly" in new Fixture {
    forAll(blockGen) { block =>
      val chain = buildBlockChain()
      chain.numHashes is 1
      val blocksSize1 = chain.numHashes
      chain.add(block, 1).isRight is true
      val blocksSize2 = chain.numHashes
      blocksSize1 + 1 is blocksSize2

      chain.getHeightUnsafe(block.hash) is (ALF.GenesisHeight + 1)
      chain.getHeight(block.hash) isE (ALF.GenesisHeight + 1)

      val diff = chain.calHashDiff(block.hash, genesis.hash).toOption.get
      diff.toAdd is AVector(block.hash)
      diff.toRemove.isEmpty is true
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val chain       = buildBlockChain()
      val blocksSize1 = chain.numHashes
      addBlocks(chain, blocks)
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2

      val midHashes = chain.getBlockHashesBetween(blocks.last.hash, blocks.head.hash)
      val expected  = blocks.tail.map(_.hash)
      midHashes isE expected
    }
  }

  it should "work correctly for a chain of blocks" in new Fixture {
    forAll(chainGenOf(4, genesis)) { blocks =>
      val chain = buildBlockChain()
      addBlocks(chain, blocks)
      val headBlock     = genesis
      val lastBlock     = blocks.last
      val chainExpected = AVector(genesis) ++ blocks

      chain.getHeight(headBlock) isE ALF.GenesisHeight
      chain.getHeight(lastBlock) isE blocks.length
      chain.getBlockSlice(headBlock) isE AVector(headBlock)
      chain.getBlockSlice(lastBlock) isE chainExpected
      chain.isTip(headBlock) is false
      chain.isTip(lastBlock) is true
      chain.getBestTipUnsafe is lastBlock.hash
      chain.maxHeight isE blocks.length
      chain.getAllTips is AVector(lastBlock.hash)

      val diff = chain.calHashDiff(blocks.last.hash, genesis.hash).toOption.get
      diff.toRemove.isEmpty is true
      diff.toAdd is blocks.map(_.hash)
    }
  }

  it should "work correctly with two chains of blocks" in new Fixture {
    forAll(chainGenOf(4, genesis)) { longChain =>
      forAll(chainGenOf(2, genesis)) { shortChain =>
        val chain = buildBlockChain()

        addBlocks(chain, shortChain)
        chain.getHeight(shortChain.head) isE 1
        chain.getHeight(shortChain.last) isE shortChain.length
        chain.getBlockSlice(shortChain.head) isE AVector(genesis, shortChain.head)
        chain.getBlockSlice(shortChain.last) isE AVector(genesis) ++ shortChain
        chain.isTip(shortChain.head) is false
        chain.isTip(shortChain.last) is true

        addBlocks(chain, longChain.init)
        chain.maxHeight isE longChain.length - 1
        chain.getAllTips.toIterable.toSet is Set(longChain.init.last.hash, shortChain.last.hash)

        chain.add(longChain.last, longChain.length)
        chain.getHeight(longChain.head) isE 1
        chain.getHeight(longChain.last) isE longChain.length
        chain.getBlockSlice(longChain.head) isE AVector(genesis, longChain.head)
        chain.getBlockSlice(longChain.last) isE AVector(genesis) ++ longChain
        chain.isTip(longChain.head) is false
        chain.isTip(longChain.last) is true
        chain.getBestTipUnsafe is longChain.last.hash
        chain.maxHeight isE longChain.length
        chain.getAllTips.toIterable.toSet is Set(longChain.last.hash, shortChain.last.hash)
      }
    }
  }

  it should "check isCanonical" in new Fixture {
    val longChain  = chainGenOf(4, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get

    val chain = buildBlockChain()
    addBlocks(chain, shortChain)
    shortChain.foreach { block =>
      chain.isCanonicalUnsafe(block.hash) is true
    }
    longChain.foreach { block =>
      chain.isCanonicalUnsafe(block.hash) is false
    }

    addBlocks(chain, longChain.init)
    shortChain.foreach { block =>
      chain.isCanonicalUnsafe(block.hash) is false
    }
    longChain.init.foreach { block =>
      chain.isCanonicalUnsafe(block.hash) is true
    }
    chain.isCanonicalUnsafe(longChain.last.hash) is false

    chain.add(longChain.last, longChain.length)
    shortChain.foreach { block =>
      chain.isCanonicalUnsafe(block.hash) is false
    }
    longChain.foreach { block =>
      chain.isCanonicalUnsafe(block.hash) is true
    }
  }

  it should "check reorg" in new Fixture {
    val longChain  = chainGenOf(3, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get

    val chain = buildBlockChain()
    addBlocks(chain, shortChain)
    chain.getHashes(ALF.GenesisHeight + 1) isE AVector(shortChain(0).hash)
    chain.getHashes(ALF.GenesisHeight + 2) isE AVector(shortChain(1).hash)

    addBlocks(chain, longChain.take(2))
    chain.getHashes(ALF.GenesisHeight + 1) isE AVector(shortChain(0).hash, longChain(0).hash)
    chain.getHashes(ALF.GenesisHeight + 2) isE AVector(shortChain(1).hash, longChain(1).hash)

    addBlocks(chain, longChain.drop(2))
    chain.getHashes(ALF.GenesisHeight + 1) isE AVector(longChain(0).hash, shortChain(0).hash)
    chain.getHashes(ALF.GenesisHeight + 2) isE AVector(longChain(1).hash, shortChain(1).hash)
    chain.getHashes(ALF.GenesisHeight + 3) isE AVector(longChain(2).hash)
  }

  it should "test chain diffs with two chains of blocks" in new Fixture {
    forAll(chainGenOf(4, genesis)) { longChain =>
      forAll(chainGenOf(3, genesis)) { shortChain =>
        val chain = buildBlockChain()
        addBlocks(chain, shortChain)
        addBlocks(chain, longChain)

        val diff0 = chain.calHashDiff(longChain.last.hash, shortChain.last.hash).toOption.get
        diff0.toRemove is shortChain.map(_.hash).reverse
        diff0.toAdd is longChain.map(_.hash)

        val diff1 = chain.calHashDiff(shortChain.last.hash, longChain.last.hash).toOption.get
        diff1.toRemove is longChain.map(_.hash).reverse
        diff1.toAdd is shortChain.map(_.hash)
      }
    }
  }

  it should "reorder hashes when there are two branches" in new Fixture {
    val shortChain = chainGenOf(2, genesis).sample.get
    val longChain  = chainGenOf(3, genesis).sample.get
    val chain      = buildBlockChain()
    addBlocks(chain, shortChain)
    chain.getHashes(ALF.GenesisHeight + 2) isE AVector(shortChain(1).hash)
    chain.maxChainWeight isE chain.getChainWeightUnsafe(shortChain.last.hash)
    addBlocks(chain, longChain)
    chain.maxChainWeight isE chain.getChainWeightUnsafe(longChain.last.hash)
    chain.getHashes(ALF.GenesisHeight + 2) isE AVector(longChain(1).hash, shortChain(1).hash)
    chain.getHashes(ALF.GenesisHeight + 3) isE AVector(longChain.last.hash)
  }

  it should "compute correct weights for a single chain" in new Fixture {
    forAll(chainGenOf(5)) { blocks =>
      val chain = createBlockChain(blocks.init)
      blocks.init.foreach(block => chain.contains(block) isE true)
      chain.maxHeight isE 3
      chain.maxWeight isE 6
      chain.add(blocks.last, 8)
      chain.contains(blocks.last) isE true
      chain.maxHeight isE 4
      chain.maxWeight isE 8
    }
  }

  it should "compute corrent weights for two chains with same root" in new Fixture {
    forAll(chainGenOf(5)) { blocks1 =>
      forAll(chainGenOf(1, blocks1.head)) { blocks2 =>
        val chain = createBlockChain(blocks1)
        addBlocks(chain, blocks2)

        blocks1.foreach(block => chain.contains(block) isE true)
        blocks2.foreach(block => chain.contains(block) isE true)
        chain.maxHeight isE 4
        chain.maxWeight isE 8
        chain.getHashesAfter(blocks1.head.hash) isE {
          val branch1 = blocks1.tail
          AVector(branch1.head.hash) ++ blocks2.map(_.hash) ++ branch1.tail.map(_.hash)
        }
        chain.getHashesAfter(blocks2.head.hash).map(_.length) isE 0
        chain.getHashesAfter(blocks1.tail.head.hash) isE blocks1.tail.tail.map(_.hash)
      }
    }
  }

  behavior of "Block tree algorithm"

  trait UnforkedFixture extends Fixture {
    val chain  = buildBlockChain()
    val blocks = chainGenOf(2, genesis).sample.get
    addBlocks(chain, blocks)
  }

  it should "test chainBack" in new UnforkedFixture {
    chain.chainBack(genesis.hash, ALF.GenesisHeight) isE AVector.empty[Hash]
    chain.chainBack(blocks.last.hash, ALF.GenesisHeight) isE blocks.map(_.hash)
    chain.chainBack(blocks.last.hash, ALF.GenesisHeight + 1) isE blocks.tail.map(_.hash)
    chain.chainBack(blocks.init.last.hash, ALF.GenesisHeight) isE blocks.init.map(_.hash)
  }

  it should "test getPredecessor" in new UnforkedFixture {
    blocks.foreach { block =>
      chain.getPredecessor(block.hash, ALF.GenesisHeight) isE genesis.hash
    }
    chain.getPredecessor(blocks.last.hash, ALF.GenesisHeight + 1) isE blocks.head.hash
  }

  it should "test getBlockHashSlice" in new UnforkedFixture {
    chain.getBlockHashSlice(blocks.last.hash) isE (genesis.hash +: blocks.map(_.hash))
    chain.getBlockHashSlice(blocks.head.hash) isE AVector(genesis.hash, blocks.head.hash)
  }

  trait ForkedFixture extends Fixture {
    val chain  = buildBlockChain()
    val chain0 = chainGenOf(2, genesis).sample.get
    val chain1 = chainGenOf(2, genesis).sample.get
    addBlocks(chain, chain0)
    addBlocks(chain, chain1)
  }

  it should "test getHashesAfter" in new ForkedFixture {
    val allHashes = (chain0 ++ chain1).map(_.hash).toSet
    chain.getHashesAfter(chain0.head.hash) isE chain0.tail.map(_.hash)
    chain.getHashesAfter(chain1.head.hash) isE chain1.tail.map(_.hash)
    chain.getHashesAfter(genesis.hash).toOption.get.toSet is allHashes

    chain.getHashesAfter(blockGen.sample.get.hash) isE AVector.empty[Hash]
  }

  it should "test isBefore" in new ForkedFixture {
    chain0.foreach { block =>
      chain.isBefore(genesis.hash, block.hash) isE true
      chain.isBefore(block.hash, genesis.hash) isE false
      chain.isBefore(block.hash, chain1.last.hash) isE false
    }
    chain1.foreach { block =>
      chain.isBefore(genesis.hash, block.hash) isE true
      chain.isBefore(block.hash, genesis.hash) isE false
      chain.isBefore(block.hash, chain0.last.hash) isE false
    }
  }

  it should "test getBlockHashesBetween" in new ForkedFixture {
    chain.getBlockHashesBetween(genesis.hash, genesis.hash) isE AVector.empty[Hash]
    chain.getBlockHashesBetween(chain0.head.hash, chain0.head.hash) isE AVector.empty[Hash]
    chain.getBlockHashesBetween(chain1.head.hash, chain1.head.hash) isE AVector.empty[Hash]

    chain.getBlockHashesBetween(chain0.last.hash, genesis.hash) isE chain0.map(_.hash)
    chain.getBlockHashesBetween(chain0.last.hash, chain0.head.hash) isE chain0.tail.map(_.hash)
    chain.getBlockHashesBetween(chain1.last.hash, genesis.hash) isE chain1.map(_.hash)
    chain.getBlockHashesBetween(chain1.last.hash, chain1.head.hash) isE chain1.tail.map(_.hash)

    chain.getBlockHashesBetween(genesis.hash, chain0.last.hash).left.value is a[IOError.Other]
    chain.getBlockHashesBetween(genesis.hash, chain1.last.hash).left.value is a[IOError.Other]
    chain.getBlockHashesBetween(chain0.head.hash, chain1.head.hash).left.value is a[IOError.Other]
  }

  it should "test calHashDiff" in new ForkedFixture {
    import BlockHashChain.ChainDiff
    val hashes0 = chain0.map(_.hash)
    val hashes1 = chain1.map(_.hash)
    chain.calHashDiff(chain0.last.hash, genesis.hash) isE ChainDiff(AVector.empty, hashes0)
    chain.calHashDiff(genesis.hash, chain0.last.hash) isE ChainDiff(hashes0.reverse, AVector.empty)
    chain.calHashDiff(chain1.last.hash, genesis.hash) isE ChainDiff(AVector.empty, hashes1)
    chain.calHashDiff(genesis.hash, chain1.last.hash) isE ChainDiff(hashes1.reverse, AVector.empty)

    val expected0 = ChainDiff(hashes1.reverse, hashes0)
    val expected1 = ChainDiff(hashes0.reverse, hashes1)
    chain.calHashDiff(chain0.last.hash, chain1.last.hash) isE expected0
    chain.calHashDiff(chain1.last.hash, chain0.last.hash) isE expected1
  }
}
