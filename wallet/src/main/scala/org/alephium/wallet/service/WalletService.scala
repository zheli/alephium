// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.wallet.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import sttp.model.StatusCode

import org.alephium.api.ApiError
import org.alephium.api.model.{Amount, Destination}
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{discard, AVector, Duration, Hex, Service, TimeStamp}
import org.alephium.wallet.Constants
import org.alephium.wallet.storage.SecretStorage
import org.alephium.wallet.web.BlockFlowClient

trait WalletService extends Service {
  import WalletService._

  def createWallet(
      password: String,
      mnemonicSize: Mnemonic.Size,
      isMiner: Boolean,
      walletName: String,
      mnemonicPassphrase: Option[String]
  ): Either[WalletError, (String, Mnemonic)]

  def restoreWallet(
      password: String,
      mnemonic: Mnemonic,
      isMiner: Boolean,
      walletName: String,
      mnemonicPassphrase: Option[String]
  ): Either[WalletError, String]

  def lockWallet(wallet: String): Either[WalletError, Unit]
  def unlockWallet(
      wallet: String,
      password: String,
      mnemonicPassphrase: Option[String]
  ): Either[WalletError, Unit]
  def deleteWallet(wallet: String, password: String): Either[WalletError, Unit]
  def getBalances(
      wallet: String,
      utxosLimit: Option[Int]
  ): Future[Either[WalletError, AVector[(Address.Asset, Amount, Amount, Option[String])]]]
  def getAddresses(wallet: String): Either[WalletError, (Address.Asset, AVector[Address.Asset])]
  def getPublicKey(wallet: String, address: Address): Either[WalletError, PublicKey]
  def getMinerAddresses(
      wallet: String
  ): Either[WalletError, AVector[AVector[(GroupIndex, Address.Asset)]]]
  def transfer(
      wallet: String,
      destinations: AVector[Destination],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Future[Either[WalletError, (Hash, Int, Int)]]
  def sweepAll(
      wallet: String,
      address: Address.Asset,
      lockTime: Option[TimeStamp],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Future[Either[WalletError, (Hash, Int, Int)]]
  def sign(
      wallet: String,
      data: String
  ): Either[WalletError, Signature]
  def deriveNextAddress(wallet: String): Either[WalletError, Address.Asset]
  def deriveNextMinerAddresses(wallet: String): Either[WalletError, AVector[Address.Asset]]
  def changeActiveAddress(wallet: String, address: Address.Asset): Either[WalletError, Unit]
  def listWallets(): Either[WalletError, AVector[(String, Boolean)]]
  def getWallet(wallet: String): Either[WalletError, (String, Boolean)]
  def revealMnemonic(wallet: String, password: String): Either[WalletError, Mnemonic]
}

object WalletService {

  sealed trait WalletError {
    def message: String
  }

  object WalletError {
    def from(error: SecretStorage.Error): WalletError =
      error match {
        case SecretStorage.Locked                   => WalletLocked
        case SecretStorage.CannotDeriveKey          => UnexpectedError
        case SecretStorage.CannotParseFile          => InvalidWalletFile
        case SecretStorage.SecretFileError          => InvalidWalletFile
        case SecretStorage.SecretFileAlreadyExists  => InvalidWalletFile
        case SecretStorage.CannotDecryptSecret      => InvalidPassword
        case SecretStorage.InvalidState             => UnexpectedError
        case SecretStorage.UnknownKey               => UnexpectedError
        case SecretStorage.SecretFileNotFound(file) => WalletNotFound(file)
      }
  }

  final case class InvalidWalletName(name: String) extends WalletError {
    val message: String = s"Invalid wallet name: $name"
  }

  final case class CannotCreateEncryptedFile(directory: Path) extends WalletError {
    val message: String = s"Cannot create encrypted file at $directory"
  }

  final case class UnknownAddress(address: Address) extends WalletError {
    val message: String = s"Unknown address: ${address.toBase58}"
  }

  case object WalletLocked extends WalletError {
    val message: String = s"Wallet is locked"
  }

  case object InvalidPassword extends WalletError {
    val message: String = s"Invalid password"
  }

  case object MinerWalletRequired extends WalletError {
    val message: String = s"Miner wallet is needed"
  }

  case object InvalidWalletFile extends WalletError {
    val message: String = s"Invalid wallet file"
  }

  case object UnexpectedError extends WalletError {
    val message: String = s"Unexpected error"
  }

  final case class WalletNotFound(file: File) extends WalletError {
    val message: String = s"Wallet ${file.getName()} not found"
  }

  final case class BlockFlowClientError(apiError: ApiError[_ <: StatusCode]) extends WalletError {
    val message: String = apiError.detail
  }

  final case class OtherError(message: String) extends WalletError

  def apply(
      blockFlowClient: BlockFlowClient,
      secretDir: Path,
      lockingTimeout: Duration
  )(implicit groupConfig: GroupConfig, executionContext: ExecutionContext): WalletService = {

    new Impl(blockFlowClient, secretDir, lockingTimeout)
  }

  final private case class StorageState(secretStorage: SecretStorage, lastAccess: TimeStamp)

  final private case class Storages(
      storages: mutable.Map[String, StorageState],
      lockingTimeout: Duration
  ) {
    def addOne(filename: String, storage: SecretStorage): Unit = {
      discard(storages.synchronized {
        storages.addOne(filename -> StorageState(storage, TimeStamp.now()))
      })
    }

    def remove(filename: String): Unit = {
      discard(storages.synchronized {
        storages.remove(filename)
      })
    }

    def get(wallet: String): Option[SecretStorage] = {
      storages.synchronized {
        storages.get(wallet).map { storageTs =>
          val lockNeeded = TimeStamp.now().deltaUnsafe(storageTs.lastAccess) > lockingTimeout &&
            !storageTs.secretStorage.isLocked()
          if (lockNeeded) {
            storageTs.secretStorage.lock()
          } else {
            storages.update(wallet, storageTs.copy(lastAccess = TimeStamp.now()))
          }
          storageTs.secretStorage
        }
      }
    }
  }

  // scalastyle:off number.of.methods
  private class Impl(
      blockFlowClient: BlockFlowClient,
      secretDir: Path,
      lockingTimeout: Duration
  )(implicit groupConfig: GroupConfig, val executionContext: ExecutionContext)
      extends WalletService {

    private val secretStorages = Storages(mutable.Map.empty, lockingTimeout)

    private val path: AVector[Int] = Constants.path

    protected def startSelfOnce(): Future[Unit] = {
      Future.fromTry(Try(discard(Files.createDirectories(secretDir))))
    }

    protected def stopSelfOnce(): Future[Unit] = {
      Future.successful(())
    }

    override val subServices: ArraySeq[Service] = ArraySeq()

    private def createOrRestoreWallet(
        password: String,
        mnemonic: Mnemonic,
        mnemonicPassphrase: Option[String],
        isMiner: Boolean,
        walletName: String
    ): Either[WalletError, (String, Mnemonic)] = {
      for {
        file <- buildWalletFile(walletName)
        storage <- SecretStorage
          .create(mnemonic, mnemonicPassphrase, password, isMiner, file, path)
          .left
          .map(_ => CannotCreateEncryptedFile(secretDir))
        _ <- if (isMiner) computeNextMinerAddresses(storage) else Right(())
      } yield {
        val fileName = file.getName
        secretStorages.addOne(fileName, storage)
        (fileName, mnemonic)
      }
    }

    override def createWallet(
        password: String,
        mnemonicSize: Mnemonic.Size,
        isMiner: Boolean,
        walletName: String,
        mnemonicPassphrase: Option[String]
    ): Either[WalletError, (String, Mnemonic)] = {
      val mnemonic = Mnemonic.generate(mnemonicSize)

      createOrRestoreWallet(password, mnemonic, mnemonicPassphrase, isMiner, walletName)
    }

    override def restoreWallet(
        password: String,
        mnemonic: Mnemonic,
        isMiner: Boolean,
        walletName: String,
        mnemonicPassphrase: Option[String]
    ): Either[WalletError, String] = {
      createOrRestoreWallet(password, mnemonic, mnemonicPassphrase, isMiner, walletName).map {
        case (name, _) =>
          name
      }
    }

    override def lockWallet(wallet: String): Either[WalletError, Unit] = {
      Right(secretStorages.get(wallet).foreach(_.lock()))
    }

    override def unlockWallet(
        wallet: String,
        password: String,
        mnemonicPassphrase: Option[String]
    ): Either[WalletError, Unit] =
      withWalletM(wallet) { secretStorage =>
        secretStorage.unlock(password, mnemonicPassphrase).left.map(WalletError.from)
      }(Left.apply)

    override def deleteWallet(wallet: String, password: String): Either[WalletError, Unit] =
      withWalletM(wallet) { secretStorage =>
        secretStorage
          .delete(password)
          .map { _ =>
            secretStorages.remove(wallet)
          }
          .left
          .map(WalletError.from)
      }(Left.apply)

    override def getBalances(
        wallet: String,
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, AVector[(Address.Asset, Amount, Amount, Option[String])]]] =
      withAddressesFut(wallet) { case (_, addresses) =>
        Future
          .sequence(
            addresses.toSeq.map(getBalance(_, utxosLimit))
          )
          .map(AVector.from(_).mapE(identity))
      }

    override def getAddresses(
        wallet: String
    ): Either[WalletError, (Address.Asset, AVector[Address.Asset])] =
      withAddresses(wallet) { addresses => Right(addresses) }

    override def getPublicKey(wallet: String, address: Address): Either[WalletError, PublicKey] = {
      withWallet(wallet) { secretStorage =>
        withPrivateKeys(secretStorage) { case (_, privateKeys) =>
          (for {
            privateKey <- privateKeys.find(privateKey =>
              Address.p2pkh(privateKey.publicKey) == address
            )
          } yield (privateKey.publicKey)).toRight(UnknownAddress(address): WalletError)
        }
      }
    }

    override def getMinerAddresses(
        wallet: String
    ): Either[WalletError, AVector[AVector[(GroupIndex, Address.Asset)]]] = {
      withMinerWallet(wallet) { storage =>
        storage.getAllPrivateKeys() match {
          case Right((_, privateKeys)) =>
            Right(
              buildMinerAddresses(privateKeys).map(_.map { case (group, address, _) =>
                (group, address)
              })
            )
          case Left(error) => Left(WalletError.from(error))
        }
      }
    }

    override def transfer(
        wallet: String,
        destinations: AVector[Destination],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice]
    ): Future[Either[WalletError, (Hash, Int, Int)]] = {
      withPrivateKeyFut(wallet) { privateKey =>
        val pubKey = privateKey.publicKey
        blockFlowClient
          .prepareTransaction(pubKey, destinations, gas, gasPrice)
          .flatMap {
            case Left(error) => Future.successful(Left(BlockFlowClientError(error)))
            case Right(buildTxResult) =>
              val signature = SignatureSchema.sign(buildTxResult.txId.bytes, privateKey.privateKey)
              blockFlowClient
                .postTransaction(buildTxResult.unsignedTx, signature, buildTxResult.fromGroup)
                .map(_.map(res => (res.txId, res.fromGroup, res.toGroup)))
                .map(_.left.map(BlockFlowClientError))
          }
      }
    }

    override def sweepAll(
        wallet: String,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice]
    ): Future[Either[WalletError, (Hash, Int, Int)]] = {
      withPrivateKeyFut(wallet) { privateKey =>
        val pubKey = privateKey.publicKey
        blockFlowClient
          .prepareSweepAllTransaction(pubKey, address, lockTime, gas, gasPrice)
          .flatMap {
            case Left(error) => Future.successful(Left(BlockFlowClientError(error)))
            case Right(buildTxResult) =>
              val signature = SignatureSchema.sign(buildTxResult.txId.bytes, privateKey.privateKey)
              blockFlowClient
                .postTransaction(buildTxResult.unsignedTx, signature, buildTxResult.fromGroup)
                .map(_.map(res => (res.txId, res.fromGroup, res.toGroup)))
                .map(_.left.map(BlockFlowClientError))
          }
      }
    }

    def sign(
        wallet: String,
        data: String
    ): Either[WalletError, Signature] = {
      withPrivateKey(wallet) { privateKey =>
        for {
          bytes <- Hex.from(data).toRight(OtherError(s"Invalid hex string"))
        } yield {
          SignatureSchema.sign(bytes, privateKey.privateKey)
        }
      }
    }

    override def deriveNextMinerAddresses(
        wallet: String
    ): Either[WalletError, AVector[Address.Asset]] = {
      withMinerWallet(wallet) { secretStorage =>
        secretStorage.getCurrentPrivateKey() match {
          case Right(activeKey) =>
            for {
              res <- computeNextMinerAddresses(secretStorage)
              _   <- secretStorage.changeActiveKey(activeKey).left.map(_ => UnexpectedError)
            } yield res
          case Left(error) => Left(WalletError.from(error))
        }
      }
    }

    override def deriveNextAddress(wallet: String): Either[WalletError, Address.Asset] = {
      withUserWallet(wallet) { secretStorage =>
        secretStorage
          .deriveNextKey()
          .map(privateKey => Address.p2pkh(privateKey.extendedPublicKey.publicKey))
          .left
          .map(WalletError.from)
      }
    }

    override def changeActiveAddress(
        wallet: String,
        address: Address.Asset
    ): Either[WalletError, Unit] = {
      withWallet(wallet) { secretStorage =>
        withPrivateKeys(secretStorage) { case (_, privateKeys) =>
          (for {
            privateKey <- privateKeys.find(privateKey =>
              Address.p2pkh(privateKey.publicKey) == address
            )
            _ <- secretStorage.changeActiveKey(privateKey).toOption
          } yield (())).toRight(UnknownAddress(address): WalletError)
        }
      }
    }

    override def listWallets(): Either[WalletError, AVector[(String, Boolean)]] = {
      listWalletsInSecretDir().flatMap { wallets =>
        wallets.mapE { wallet =>
          withWallet(wallet) { secret => Right(secret.isLocked()) }.map { locked =>
            (wallet, locked)
          }
        }
      }
    }

    override def getWallet(wallet: String): Either[WalletError, (String, Boolean)] = {
      withWallet(wallet) { secretStorage =>
        Right((wallet, secretStorage.isLocked()))
      }
    }

    override def revealMnemonic(wallet: String, password: String): Either[WalletError, Mnemonic] = {
      withWallet(wallet) { secretStorage =>
        secretStorage
          .revealMnemonic(password)
          .left
          .map(WalletError.from)
      }
    }

    private def listWalletsInSecretDir(): Either[WalletError, AVector[String]] = {
      val dir = secretDir.toFile
      Either.cond(
        dir.exists && dir.isDirectory,
        AVector.from(dir.listFiles.filter(_.isFile).map(_.getName)),
        UnexpectedError
      )
    }

    private def getBalance(
        address: Address.Asset,
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, (Address.Asset, Amount, Amount, Option[String])]] = {
      blockFlowClient
        .fetchBalance(address, utxosLimit)
        .map(
          _.map { case (amount, lockedAmount, warning) =>
            (address, amount, lockedAmount, warning)
          }.left.map(error => BlockFlowClientError(error))
        )
    }

    private def withWalletM[A, M[_]](
        wallet: String
    )(f: SecretStorage => M[A])(errorWrapper: WalletError => M[A]): M[A] = {
      secretStorages.get(wallet) match {
        case None =>
          val file = new File(s"$secretDir/$wallet")
          SecretStorage.load(file, path) match {
            case Right(secretStorage) =>
              secretStorages.addOne(wallet, secretStorage)
              f(secretStorage)
            case Left(error) =>
              errorWrapper(WalletError.from(error))
          }
        case Some(secretStorage) => f(secretStorage)
      }
    }

    private def checkIsMiner(
        storage: SecretStorage,
        isMiner: Boolean
    ): Either[WalletError, Unit] = {
      storage
        .isMiner()
        .left
        .map(WalletError.from)
        .flatMap { state => if (state == isMiner) Right(()) else Left(MinerWalletRequired) }
    }

    private def withWallet[A](
        wallet: String
    )(f: SecretStorage => Either[WalletError, A]): Either[WalletError, A] = {
      withWalletM(wallet)(storage => f(storage))(Left.apply)
    }

    private def withSpecificWallet[A](wallet: String, isMiner: Boolean)(
        f: SecretStorage => Either[WalletError, A]
    ): Either[WalletError, A] = {
      withWalletM(wallet)(storage => checkIsMiner(storage, isMiner).flatMap(_ => f(storage)))(
        Left.apply
      )
    }

    private def withUserWallet[A](
        wallet: String
    )(f: SecretStorage => Either[WalletError, A]): Either[WalletError, A] = {
      withSpecificWallet(wallet, isMiner = false)(f)
    }

    private def withMinerWallet[A](
        wallet: String
    )(f: SecretStorage => Either[WalletError, A]): Either[WalletError, A] = {
      withSpecificWallet(wallet, isMiner = true)(f)
    }

    private def withWalletFut[A](
        wallet: String
    )(f: SecretStorage => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] = {
      withWalletM(wallet)(storage => f(storage))(error => Future.successful(Left(error)))
    }

    private def withPrivateKey[A](
        wallet: String
    )(f: ExtendedPrivateKey => Either[WalletError, A]): Either[WalletError, A] =
      withWallet(wallet)(_.getCurrentPrivateKey() match {
        case Left(error)       => Left(WalletError.from(error))
        case Right(privateKey) => f(privateKey)
      })

    private def withPrivateKeyFut[A](
        wallet: String
    )(f: ExtendedPrivateKey => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      withWalletFut(wallet)(_.getCurrentPrivateKey() match {
        case Left(error)       => Future.successful(Left(WalletError.from(error)))
        case Right(privateKey) => f(privateKey)
      })

    private def withPrivateKeysM[A, M[_]](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => M[A]
    )(errorWrapper: WalletError => M[A]): M[A] =
      (for {
        privateKeys <- storage.getAllPrivateKeys()
        isMiner     <- storage.isMiner()
      } yield {
        (privateKeys, isMiner)
      }) match {
        case Left(error) => errorWrapper(WalletError.from(error))
        case Right((privateKeys, isMiner)) =>
          if (isMiner) {
            f((privateKeys._1, buildMinerPrivateKeys(privateKeys._2)))
          } else {
            f(privateKeys)
          }
      }

    private def withAllMinerPrivateKeysM[A, M[_]](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => M[A]
    )(errorWrapper: WalletError => M[A]): M[A] =
      (for {
        _           <- checkIsMiner(storage, true)
        privateKeys <- storage.getAllPrivateKeys().left.map(WalletError.from)
      } yield {
        privateKeys
      }) match {
        case Left(error) => errorWrapper(error)
        case Right(privateKeys) =>
          f(privateKeys)
      }

    private def withPrivateKeys[A](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => Either[WalletError, A]
    ): Either[WalletError, A] =
      withPrivateKeysM(storage)(f)(Left.apply)

    private def withAllMinerPrivateKeys[A](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => Either[WalletError, A]
    ): Either[WalletError, A] =
      withAllMinerPrivateKeysM(storage)(f)(Left.apply)

    private def withAddressesM[A, M[_]](
        wallet: String
    )(
        f: ((Address.Asset, AVector[Address.Asset])) => M[A]
    )(errorWrapper: WalletError => M[A]): M[A] =
      withWalletM(wallet) { storage =>
        withPrivateKeysM(storage) { case (active, privateKeys) =>
          val activeAddress = Address.p2pkh(active.publicKey)
          val addresses =
            privateKeys.map(privateKey => Address.p2pkh(privateKey.publicKey))
          f((activeAddress, addresses))
        }(errorWrapper)
      }(errorWrapper)

    private def withAddresses[A](
        wallet: String
    )(
        f: ((Address.Asset, AVector[Address.Asset])) => Either[WalletError, A]
    ): Either[WalletError, A] =
      withAddressesM(wallet)(f)(Left.apply)

    private def withAddressesFut[A](wallet: String)(
        f: ((Address.Asset, AVector[Address.Asset])) => Future[Either[WalletError, A]]
    ): Future[Either[WalletError, A]] =
      withAddressesM(wallet)(f)(error => Future.successful(Left(error)))

    private def buildWalletFile(walletName: String): Either[WalletError, File] = {
      val regex = "^[a-zA-Z0-9_-]*$".r
      Either.cond(
        regex.matches(walletName),
        new File(s"$secretDir/$walletName"),
        InvalidWalletName(walletName)
      )
    }

    private def buildMinerAddresses(
        privateKeys: AVector[ExtendedPrivateKey]
    ): AVector[AVector[(GroupIndex, Address.Asset, ExtendedPrivateKey)]] = {
      val addresses =
        privateKeys.map(privateKey => (Address.p2pkh(privateKey.publicKey), privateKey))

      val addressByGroup = addresses.toSeq.groupBy(_._1.groupIndex)
      val smallestIndex  = addressByGroup.values.map(_.length).minOption.getOrElse(1)

      var res: Seq[Seq[(GroupIndex, (Address.Asset, ExtendedPrivateKey))]]    = Seq.empty
      var addForGroup: Seq[(GroupIndex, (Address.Asset, ExtendedPrivateKey))] = Seq.empty
      (0 until smallestIndex).foreach { index =>
        (0 until groupConfig.groups).foreach { group =>
          val groupIndex = GroupIndex.unsafe(group)
          addressByGroup.get(groupIndex).flatMap(_.lift(index)).foreach { address =>
            addForGroup = addForGroup :+ ((groupIndex, address))
          }
        }
        res = res :+ addForGroup
        addForGroup = Seq.empty
      }
      AVector.from(
        res.map(l =>
          AVector.from(l.map { case (group, (address, privateKey)) =>
            (group, address, privateKey)
          })
        )
      )
    }

    private def buildMinerPrivateKeys(
        privateKeys: AVector[ExtendedPrivateKey]
    ): AVector[ExtendedPrivateKey] = {
      buildMinerAddresses(privateKeys).flatMap(_.map { case (_, _, privateKey) => privateKey })
    }

    private def computeNextMinerAddresses(
        storage: SecretStorage
    ): Either[WalletError, AVector[Address.Asset]] = {
      withAllMinerPrivateKeys(storage) { case (_, keys) =>
        val addressByGroup = keys.toSeq
          .map { privateKey =>
            Address.p2pkh(privateKey.extendedPublicKey.publicKey)
          }
          .groupBy(_.groupIndex)

        if (addressByGroup.keys.size < groupConfig.groups) {
          computeNextMinerAddressesWithIndex(addressByGroup, storage, 0)
        } else {
          val smallestIndex = addressByGroup.values.map(_.length).minOption.getOrElse(0)
          computeNextMinerAddressesWithIndex(addressByGroup, storage, smallestIndex)
        }
      }
    }

    @tailrec
    private def computeNextMinerAddressesWithIndex(
        addressByGroup: Map[GroupIndex, Seq[Address.Asset]],
        storage: SecretStorage,
        indexWanted: Int
    ): Either[WalletError, AVector[Address.Asset]] = {
      val addresses = addressByGroup.values.map(_.lift(indexWanted))
      if (addressByGroup.keys.size < groupConfig.groups || addresses.exists(_.isEmpty)) {
        storage
          .deriveNextKey() match {
          case Left(error) => Left(WalletError.from(error))
          case Right(nextKey) =>
            computeNextMinerAddressesWithIndex(
              updateAddressByGroup(addressByGroup, nextKey),
              storage,
              indexWanted
            )
        }
      } else {
        Right(AVector.from(addresses.flatten))
      }
    }

    private def updateAddressByGroup(
        addressByGroup: Map[GroupIndex, Seq[Address.Asset]],
        privateKey: ExtendedPrivateKey
    ): Map[GroupIndex, Seq[Address.Asset]] = {
      val address = Address.p2pkh(privateKey.extendedPublicKey.publicKey)
      val group   = address.groupIndex
      val newKeys = addressByGroup.get(group) match {
        case None       => Seq(address)
        case Some(keys) => keys :+ address
      }
      addressByGroup + ((group, newKeys))
    }
  }
}
