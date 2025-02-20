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

package org.alephium.app

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import akka.Done
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.io.Tcp
import akka.testkit.TestProbe
import io.vertx.core.Vertx
import io.vertx.core.http.WebSocketBase
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import sttp.model.StatusCode
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.ApiModelCodec
import org.alephium.api.UtilJson.avectorWriter
import org.alephium.api.model._
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.MiningBlob
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.http.HttpFixture
import org.alephium.json.Json._
import org.alephium.protocol.{ALF, PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model.{Address, Block, ChainIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._
import org.alephium.wallet
import org.alephium.wallet.api.model._

// scalastyle:off method.length
// scalastyle:off number.of.methods
class CliqueFixture(implicit spec: AlephiumActorSpec)
    extends AlephiumSpec
    with ItConfigFixture
    with NumericHelpers
    with ApiModelCodec
    with wallet.json.ModelCodecs
    with HttpFixture
    with ScalaFutures
    with Eventually { Fixture =>
  implicit val system: ActorSystem = spec.system

  private val vertx      = Vertx.vertx()
  private val httpClient = vertx.createHttpClient()

  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))
  implicit lazy val apiConfig = ApiConfig.load(newConfig)

  lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

  def generateAccount: (String, String, String) = {
    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    (Address.p2pkh(pubKey).toBase58, pubKey.toHexString, priKey.toHexString)
  }

  val address    = "14PqtYSSbwpUi2RJKUvv9yUwGafd6yHbEcke7ionuiE7w"
  val publicKey  = "03e75902fa24caff042b2b4c350e8f2ffeb3cb95f4263f0e109a2c2d7aa3dcae5c"
  val privateKey = "d24967efb7f1b558ad40a4d71593ceb5b3cecf46d17f0e68ef53def6b391c33d"
  val mnemonic =
    "toward outdoor daughter deny mansion bench water alien crumble mother exchange screen salute antenna abuse key hair crisp debate goose great market core screen"
  val (transferAddress, _, _) = generateAccount

  val password = "password"

  val initialBalance = Balance.from(Amount(genesisBalance), Amount.Zero, 1)
  val transferAmount = ALF.alf(1)

  val defaultMasterPort     = generatePort()
  val defaultRestMasterPort = restPort(defaultMasterPort)
  val defaultWsMasterPort   = wsPort(defaultMasterPort)
  val defaultWalletPort     = generatePort()

  val blockNotifyProbe = TestProbe()

  def unitRequest(request: Int => HttpRequest, port: Int = defaultRestMasterPort): Assertion = {
    val response = request(port).send(backend).futureValue
    response.code is StatusCode.Ok
  }

  def request[T: Reader](request: Int => HttpRequest, port: Int): T = {
    eventually {
      val response = request(port).send(backend).futureValue

      val body = response.body match {
        case Right(r) => r
        case Left(l)  => l
      }
      read[T](body)
    }
  }

  def requestFailed(
      request: Int => HttpRequest,
      port: Int = defaultRestMasterPort,
      statusCode: StatusCode
  ): Assertion = {
    val response = request(port).send(backend).futureValue
    response.code is statusCode
  }

  def transfer(
      fromPubKey: String,
      toAddress: String,
      amount: U256,
      privateKey: String,
      restPort: Int
  ): TxResult = eventually {
    val buildTx    = buildTransaction(fromPubKey, toAddress, amount)
    val unsignedTx = request[BuildTransactionResult](buildTx, restPort)
    val submitTx   = submitTransaction(unsignedTx, privateKey)
    val res        = request[TxResult](submitTx, restPort)
    res
  }

  def mineAndAndOneBlock(server: Server, index: ChainIndex): Block = {
    val blockFlow = server.node.blockFlow
    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(index, LockupScript.p2pkh(genesisKeys(index.to.value)._2))
    val miningBlob = MiningBlob.from(blockTemplate)

    @tailrec
    def mine(): Block = {
      Miner.mine(index, miningBlob)(server.config.broker, server.config.mining) match {
        case Some((block, _)) => block
        case None             => mine()
      }
    }

    val block = mine()
    server.node.blockFlow.addAndUpdateView(block) isE ()
    block
  }

  def confirmTx(tx: TxResult, restPort: Int): Assertion = eventually {
    val txStatus = request[TxStatus](getTransactionStatus(tx), restPort)
    checkConfirmations(txStatus)
  }

  def confirmTx(tx: Transfer.Result, restPort: Int): Assertion = eventually {
    val txStatus = request[TxStatus](getTransactionStatus(tx), restPort)
    checkConfirmations(txStatus)
  }

  def checkConfirmations(txStatus: TxStatus): Assertion = {
    print(txStatus) // keep this for easier CI analysis
    print("\n")

    txStatus is a[Confirmed]
    val confirmed = txStatus.asInstanceOf[Confirmed]
    confirmed.chainConfirmations > 1 is true
    confirmed.fromGroupConfirmations > 1 is true
    confirmed.toGroupConfirmations > 1 is true
  }

  def transferFromWallet(toAddress: String, amount: U256, restPort: Int): Transfer.Result =
    eventually {
      val walletName = "wallet-name"

      request[WalletRestore.Result](restoreWallet(password, mnemonic, walletName), restPort)
      val transfer = transferWallet(walletName, toAddress, amount)
      val res      = request[Transfer.Result](transfer, restPort)
      res
    }

  final def awaitNBlocksPerChain(number: Int): Unit = {
    val buffer  = Array.fill(groups0)(Array.ofDim[Int](groups0))
    val timeout = Duration.ofMinutesUnsafe(2).asScala

    @tailrec
    def iter(): Unit = {
      blockNotifyProbe.receiveOne(max = timeout) match {
        case text: String =>
          val notification = read[NotificationUnsafe](text).asNotification.toOption.get
          val blockEntry   = read[BlockEntry](notification.params)
          buffer(blockEntry.chainFrom)(blockEntry.chainTo) += 1
          if (buffer.forall(_.forall(_ >= number))) () else iter()
      }
    }

    iter()
  }

  @tailrec
  final def awaitNBlocks(number: Int): Unit = {
    assume(number > 0)
    val timeout = Duration.ofMinutesUnsafe(2).asScala
    blockNotifyProbe.receiveOne(max = timeout) match {
      case _: String =>
        if (number <= 1) {
          ()
        } else {
          awaitNBlocks(number - 1)
        }
    }
  }

  def buildEnv(
      publicPort: Int,
      masterPort: Int,
      walletPort: Int,
      brokerId: Int,
      brokerNum: Int,
      bootstrap: Option[InetSocketAddress],
      configOverrides: Map[String, Any]
  ) = {
    new ItConfigFixture with StoragesFixture {
      override val configValues = Map(
        ("alephium.network.bind-address", s"127.0.0.1:$publicPort"),
        ("alephium.network.internal-address", s"127.0.0.1:$publicPort"),
        ("alephium.network.coordinator-address", s"127.0.0.1:$masterPort"),
        ("alephium.network.external-address", s"127.0.0.1:$publicPort"),
        ("alephium.network.ws-port", wsPort(publicPort)),
        ("alephium.network.rest-port", restPort(publicPort)),
        ("alephium.network.miner-api-port", minerPort(publicPort)),
        ("alephium.broker.broker-num", brokerNum),
        ("alephium.broker.broker-id", brokerId),
        ("alephium.consensus.block-target-time", "1 seconds"),
        ("alephium.consensus.num-zeros-at-least-in-hash", "8"),
        ("alephium.mining.batch-delay", "200 milli"),
        ("alephium.wallet.port", walletPort),
        ("alephium.wallet.secret-dir", s"${java.nio.file.Files.createTempDirectory("it-test")}")
      ) ++ configOverrides
      implicit override lazy val config = {
        val minerAddresses =
          genesisKeys.map(p => Address.Asset(LockupScript.p2pkh(p._2)))

        val tmp0 = AlephiumConfig.load(newConfig)
        val tmp1 = tmp0.copy(mining = tmp0.mining.copy(minerAddresses = Some(minerAddresses)))
        bootstrap match {
          case Some(address) =>
            tmp1.copy(discovery = tmp1.discovery.copy(bootstrap = ArraySeq(address)))
          case None => tmp1
        }
      }

      val storages: Storages = StoragesFixture.buildStorages(rootPath)
    }
  }

  def bootClique(
      nbOfNodes: Int,
      bootstrap: Option[InetSocketAddress] = None,
      connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply,
      configOverrides: Map[String, Any] = Map.empty
  ): Clique = {
    val masterPort = generatePort()

    val servers: Seq[Server] = (0 until nbOfNodes).map { brokerId =>
      val publicPort = if (brokerId equals 0) masterPort else generatePort()
      bootNode(
        publicPort = publicPort,
        masterPort = masterPort,
        brokerId = brokerId,
        walletPort = generatePort(),
        bootstrap = bootstrap,
        brokerNum = nbOfNodes,
        connectionBuild = connectionBuild,
        configOverrides = configOverrides
      )
    }

    Clique(AVector.from(servers))
  }

  def bootNode(
      publicPort: Int,
      brokerId: Int,
      brokerNum: Int = 2,
      masterPort: Int = defaultMasterPort,
      walletPort: Int = defaultWalletPort,
      bootstrap: Option[InetSocketAddress] = None,
      connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply,
      configOverrides: Map[String, Any] = Map.empty
  ): Server = {
    val platformEnv =
      buildEnv(publicPort, masterPort, walletPort, brokerId, brokerNum, bootstrap, configOverrides)

    val server: Server = new Server {
      val flowSystem: ActorSystem =
        ActorSystem(s"flow-${Random.nextInt()}", platformEnv.newConfig)
      implicit val executionContext: ExecutionContext = flowSystem.dispatcher

      val defaultNetwork = platformEnv.config.network
      val network        = defaultNetwork.copy(connectionBuild = connectionBuild)

      implicit val config    = platformEnv.config.copy(network = network)
      implicit val apiConfig = ApiConfig.load(platformEnv.newConfig)
      val storages           = platformEnv.storages

      override lazy val blocksExporter: BlocksExporter =
        new BlocksExporter(node.blockFlow, rootPath)(config.broker)

      CoordinatedShutdown(flowSystem).addTask(
        CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
        "Shutdown services"
      ) { () =>
        for {
          _ <- this.stopSubServices()
        } yield Done
      }

      override def stop(): Future[Unit] = flowSystem.terminate().map(_ => ())
    }

    server
  }

  def startWS(port: Int): Future[WebSocketBase] = {
    httpClient
      .webSocket(port, "127.0.0.1", "/events")
      .asScala
      .map { ws =>
        ws.textMessageHandler { blockNotify =>
          blockNotifyProbe.ref ! blockNotify
        }
      }(system.dispatcher)
  }

  def jsonRpc(method: String, params: String): String =
    s"""{"jsonrpc":"2.0","id": 0,"method":"$method","params": $params}"""

  val getSelfClique =
    httpGet(s"/infos/self-clique")

  val getInterCliquePeerInfo =
    httpGet(s"/infos/inter-clique-peer-info")

  val getDiscoveredNeighbors =
    httpGet(s"/infos/discovered-neighbors")

  val getMisbehaviors =
    httpGet(s"/infos/misbehaviors")

  def getGroup(address: String) =
    httpGet(s"/addresses/$address/group")

  def getBalance(address: String) =
    httpGet(s"/addresses/$address/balance")

  def getChainInfo(fromGroup: Int, toGroup: Int) =
    httpGet(s"/blockflow/chain-info?fromGroup=$fromGroup&toGroup=$toGroup")

  def buildTransaction(fromPubKey: String, toAddress: String, amount: U256) =
    httpPost(
      "/transactions/build",
      Some(s"""
        |{
        |  "fromPublicKey": "$fromPubKey",
        |  "destinations": [
        |    {
        |      "address": "$toAddress",
        |      "amount": "$amount",
        |      "tokens": []
        |    }
        |  ]
        |}
        """.stripMargin)
    )

  def buildMultisigTransaction(
      fromAddress: String,
      fromPublicKeys: AVector[String],
      toAddress: String,
      amount: U256
  ) = {
    val body = s"""
        |{
        |  "fromAddress": "$fromAddress",
        |  "fromPublicKeys": ${write(fromPublicKeys)},
        |  "destinations": [
        |    {
        |      "address": "$toAddress",
        |      "amount": "$amount"
        |    }
        |  ]
        |}
        """.stripMargin

    httpPost(
      "/multisig/build",
      Some(body)
    )
  }

  def createWallet(password: String, walletName: String) =
    httpPost(
      s"/wallets",
      Some(s"""{"password":"${password}","walletName":"$walletName"}""")
    )

  def restoreWallet(password: String, mnemonic: String, walletName: String) =
    httpPut(
      s"/wallets",
      Some(s"""{"password":"${password}","mnemonic":"${mnemonic}","walletName":"$walletName"}""")
    )

  def transferWallet(walletName: String, address: String, amount: U256) = {
    httpPost(
      s"/wallets/${walletName}/transfer",
      Some(s"""{"destinations":[{"address":"${address}","amount":"${amount}","tokens":[]}]}""")
    )
  }

  def getAddresses(walletName: String) = {
    httpGet(
      s"/wallets/${walletName}/addresses"
    )
  }

  def getAddressInfo(walletName: String, address: String) = {
    httpGet(
      s"/wallets/${walletName}/addresses/$address"
    )
  }

  def sign(walletName: String, data: String) = {
    httpPost(
      s"/wallets/${walletName}/sign",
      Some(s"""{"data":"$data"}""")
    )
  }

  def submitTransaction(buildTransactionResult: BuildTransactionResult, privateKey: String) = {
    val signature: Signature = SignatureSchema.sign(
      buildTransactionResult.txId.bytes,
      PrivateKey.unsafe(Hex.unsafe(privateKey))
    )
    httpPost(
      "/transactions/submit",
      Some(
        s"""{"unsignedTx":"${buildTransactionResult.unsignedTx}","signature":"${signature.toHexString}"}"""
      )
    )
  }

  def signAndSubmitMultisigTransaction(
      buildTransactionResult: BuildTransactionResult,
      privateKeys: AVector[String]
  ) = {
    val signatures: AVector[Signature] = privateKeys.map { p =>
      SignatureSchema.sign(
        buildTransactionResult.txId.bytes,
        PrivateKey.unsafe(Hex.unsafe(p))
      )
    }
    submitMultisigTransaction(
      buildTransactionResult,
      signatures
    )
  }

  def submitMultisigTransaction(
      buildTransactionResult: BuildTransactionResult,
      signatures: AVector[Signature]
  ) = {
    val body =
      s"""{"unsignedTx":"${buildTransactionResult.unsignedTx}","signatures":${write(
        signatures.map(_.toHexString)
      )}}"""
    httpPost(
      "/multisig/submit",
      Some(
        body
      )
    )
  }

  def getTransactionStatus(tx: TxResult) = {
    httpGet(
      s"/transactions/status?txId=${tx.txId.toHexString}&fromGroup=${tx.fromGroup}&toGroup=${tx.toGroup}"
    )
  }
  def getTransactionStatus(tx: Transfer.Result) = {
    httpGet(
      s"/transactions/status?txId=${tx.txId.toHexString}&fromGroup=${tx.fromGroup}&toGroup=${tx.toGroup}"
    )
  }

  def compileFilang(code: String) = {
    httpPost(s"/contracts/compile", Some(code))
  }

  def multisig(keys: AVector[String], mrequired: Int) = {
    val body = s"""
        |{
        |  "keys": ${write(keys)},
        |  "mrequired": $mrequired
        |}
        """.stripMargin
    httpPost(s"/multisig/address", maybeBody = Some(body))
  }

  def decodeUnsignedTransaction(unsignedTx: String) = {
    val body = s"""
        |{
        |  "unsignedTx": "$unsignedTx"
        |}
        """.stripMargin
    httpPost(s"/transactions/decode", maybeBody = Some(body))
  }

  def buildContract(query: String) = {
    httpPost(s"/contracts/build", Some(query))
  }

  def submitContract(contract: String) = {
    httpPost(s"/contracts/submit", Some(contract))
  }

  val startMining = httpPost("/miners?action=start-mining")
  val stopMining  = httpPost("/miners?action=stop-mining")

  def exportBlocks(filename: String) =
    httpPost(s"/export-blocks", Some(s"""{"filename": "${filename}"}"""))

  def blockflowFetch(fromTs: TimeStamp, toTs: TimeStamp) =
    httpGet(s"/blockflow?fromTs=${fromTs.millis}&toTs=${toTs.millis}")

  case class Clique(servers: AVector[Server]) {
    def coordinator    = servers.head
    def masterTcpPort  = servers.head.config.network.coordinatorAddress.getPort
    def masterRestPort = servers.head.config.network.restPort

    def brokers: Int         = servers.head.config.broker.brokerNum
    def groupsPerBroker: Int = servers.head.config.broker.groupNumPerBroker

    def getGroup(address: String) =
      request[Group](Fixture.getGroup(address), masterRestPort)

    def getServer(fromGroup: Int): Server = servers(fromGroup % brokers)
    def getRestPort(fromGroup: Int): Int  = getServer(fromGroup).config.network.restPort

    def start(): Unit = {
      servers.map(_.start()).foreach(_.futureValue is ())
      servers.foreach { server =>
        eventually(
          request[SelfClique](getSelfClique, server.config.network.restPort).synced is true
        )
      }
    }

    def stop(): Unit = {
      servers.map(_.stop()).foreach(_.futureValue is ())
    }

    def startWs(): Unit = {
      servers.foreach { server =>
        startWS(server.config.network.wsPort)
      }
    }

    def startMining(): Unit = {
      servers.foreach { server =>
        request[Boolean](Fixture.startMining, server.config.network.restPort) is true
      }
    }

    def stopMining(): Unit = {
      servers.foreach { server =>
        request[Boolean](
          Fixture.stopMining,
          restPort(server.config.network.bindAddress.getPort)
        ) is true
      }
    }

    def selfClique(): SelfClique = {
      request[SelfClique](Fixture.getSelfClique, servers.sample().config.network.restPort)
    }
  }

  def checkTx(tx: TxResult, port: Int, status: TxStatus): Assertion = {
    eventually(
      request[TxStatus](getTransactionStatus(tx), port) is status
    )
  }
}
// scalastyle:on method.length
