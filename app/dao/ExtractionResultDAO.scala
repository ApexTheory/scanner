package dao

import javax.inject.Inject
import models.{ExtractedAssetModel, ExtractedBlockModel, ExtractedDataInputModel, ExtractedInputModel, ExtractedOutputModel, ExtractedRegisterModel, ExtractedTransactionModel, ExtractionInputResultModel, ExtractionOutputResultModel}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils
import com.google.common.hash.{BloomFilter, Funnels}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}


class ExtractionResultDAO @Inject() (extractedBlockDAO: ExtractedBlockDAO, transactionDAO: TransactionDAO, dataInputDAO: DataInputDAO, inputDAO: InputDAO, outputDAO: OutputDAO, assetDAO: AssetDAO, registerDAO: RegisterDAO, protected val dbConfigProvider: DatabaseConfigProvider) (implicit executionContext: ExecutionContext)
    extends DbUtils with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._
  /**
  * Spend outputs, that have appeared here as inputs and store these inputs and transactions into db as transactionally.
   * @param extractedInputs : ExtractionInputResultModel extracted Inputs in one block
   */
  def spendOutputsAndStoreRelatedData(extractedInputs: Seq[ExtractionInputResultModel]): Unit = {
    var transactions: Seq[ExtractedTransactionModel] = Seq()
    var dataInputs: Seq[ExtractedDataInputModel] = Seq()
    var inputs: Seq[ExtractedInputModel] = Seq()

    extractedInputs.foreach(obj => {
      inputs = inputs :+ obj.extractedInput
      dataInputs = dataInputs ++ obj.extractedDataInput
      transactions = transactions :+ obj.extractedTransaction
    })

    val inputsIds = inputs.map(_.boxId)
    val updateAndGetQuery = for {
      outputs <- DBIO.successful(outputDAO.outputs.filter(_.boxId inSet inputsIds))
      _ <- outputs.map(_.spent).update(true)
      result <- outputs.map(_.boxId).result
    } yield result

    val bfInputsTxIds = BloomFilter.create[String](Funnels.byteArrayFunnel(), 20000, 0.01)

    val action = for {
      responseUpdateAndGetQuery <- updateAndGetQuery.transactionally
      filteredInputs <- DBIO.successful(inputs.filter( input => {
        if (responseUpdateAndGetQuery.contains(input.boxId)){
          bfInputsTxIds.put(input.txId)
          true
        }
        else false
      }))
      transactions <- DBIO.successful(transactions.filter(n => bfInputsTxIds.mightContain(n.id)))
      _ <- transactionDAO.insertIfDoesNotExist(transactions.distinct)
      dataInputs <- DBIO.successful(dataInputs.filter(n => bfInputsTxIds.mightContain(n.txId)))
      _ <- dataInputDAO.insertIfDoesNotExist(dataInputs.distinct)
      _ <- inputDAO.insert(filteredInputs)
    } yield {

    }
    val response = execTransact(action)
    Await.result(response, Duration.Inf)
  }

  /**
  * Store extracted Block also store outputs, transactions are scanned according to rules into db as transactionally.
   * @param createdOutputs : ExtractionOutputResultModel extracted outputs
   * @param extractedBlockModel: ExtractedBlockModel extracted block
   */
  def storeOutputsAndRelatedData(createdOutputs: Seq[ExtractionOutputResultModel], extractedBlockModel: ExtractedBlockModel): Unit = {
    var transactions: Seq[ExtractedTransactionModel] = Seq()
    var dataInputs: Seq[ExtractedDataInputModel] = Seq()
    var outputs: Seq[ExtractedOutputModel] = Seq()
    var assets: Seq[ExtractedAssetModel] = Seq()
    var registers: Seq[ExtractedRegisterModel] = Seq()

    createdOutputs.foreach(obj => {
      outputs = outputs :+ obj.extractedOutput
      transactions = transactions :+ obj.extractedTransaction
      dataInputs = dataInputs ++ obj.extractedDataInput
      assets = assets ++ obj.extractedAssets
      registers = registers ++ obj.extractedRegisters
    })

    val action = for {
        _ <- extractedBlockDAO.insert(Seq(extractedBlockModel))
        _ <- transactionDAO.insertIfDoesNotExist(transactions.distinct)
        _ <- dataInputDAO.insertIfDoesNotExist(dataInputs.distinct)
        _ <- outputDAO.insert(outputs)
        _ <- assetDAO.insert(assets)
        _ <- registerDAO.insert(registers)
    } yield {

    }
    val response = execTransact(action)
    Await.result(response, Duration.Inf)
  }
}
