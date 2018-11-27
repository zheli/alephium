package org.alephium.flow.storage

import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction, TxInput}
import org.alephium.util.AVector

trait BlockPool extends BlockHashPool {

  def numTransactions: Int

  def contains(block: Block): Boolean = contains(block.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: Keccak256): IOResult[Block]

  // Assuming the block is verified
  def add(block: Block, weight: Int): IOResult[Unit]

  // Assuming the block is verified
  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit]

  def getBlocks(locators: AVector[Keccak256]): IOResult[AVector[Block]] = {
    locators.filter(contains).traverse(getBlock)
  }

  def getHeight(block: Block): Int = getHeight(block.hash)

  def getWeight(block: Block): Int = getWeight(block.hash)

  // TODO: use ChainSlice instead of AVector[Block]
  def getBlockSlice(hash: Keccak256): IOResult[AVector[Block]] = {
    getBlockHashSlice(hash).traverse(getBlock)
  }
  def getBlockSlice(block: Block): IOResult[AVector[Block]] = getBlockSlice(block.hash)

  def isTip(block: Block): Boolean = isTip(block.hash)

  // TODO: have a safe version
  def getTransaction(hash: Keccak256): Transaction

  def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.inputs.sumBy {
      case TxInput(txHash, outputIndex) =>
        val tx       = getTransaction(txHash)
        val txOutput = tx.unsigned.outputs(outputIndex)
        if (txOutput.publicKey == address) txOutput.value else BigInt(0)
    }
  }

  def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.outputs.filter(_.publicKey == address).sumBy(_.value)
  }

  def getBalance(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    getTxOutputValue(transaction, address) - getTxInputValue(transaction, address)
  }

  def getBalance(block: Block, address: ED25519PublicKey): BigInt = {
    block.transactions.sumBy(transaction => getBalance(transaction, address))
  }
}

sealed trait AddBlockResult

object AddBlockResult {
  sealed trait OK     extends AddBlockResult
  case object Success extends OK
  case object AlreadyExisted extends OK {
    override def toString: String = "Block already exist"
  }

  // needs more data for verification
  sealed trait Incomplete                                              extends AddBlockResult
  case class HeaderIncomplete(result: AddBlockHeaderResult.Incomplete) extends Incomplete

  sealed trait Error                                         extends AddBlockResult
  case class HeaderError(result: AddBlockHeaderResult.Error) extends Error
  case class IOErrorForBlock(error: IOError)                 extends Error
  case class Other(message: String) extends Error {
    override def toString: String = s"Failed in adding block: $message"
  }
}
