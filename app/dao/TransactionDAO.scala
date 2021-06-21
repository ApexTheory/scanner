package dao

import javax.inject.{Inject, Singleton}
import models.ExtractedTransactionModel
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.duration._

import scala.concurrent.{Await, ExecutionContext, Future}

trait TransactionComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  class TransactionTable(tag: Tag) extends Table[ExtractedTransactionModel](tag, "TRANSACTIONS") {
    def id = column[String]("ID")
    def headerId = column[String]("HEADER_ID")
    def inclusionHeight = column[Int]("INCLUSION_HEIGHT")
    def timestamp = column[Long]("TIMESTAMP")
    def mainChain = column[Boolean]("MAIN_CHAIN", O.Default(true))
    def * = (id, headerId, inclusionHeight, timestamp, mainChain) <> (ExtractedTransactionModel.tupled, ExtractedTransactionModel.unapply)
  }
}

@Singleton()
class TransactionDAO @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
  extends TransactionComponent
    with DbUtils
    with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  val transactions = TableQuery[TransactionTable]

  /**
   * inserts a tx into db
   * @param transaction transaction
   */
  def insert(transaction: ExtractedTransactionModel): Future[Unit] = db.run(transactions += transaction).map(_ => ())

  /**
   * create query for insert data
   * @param transactions transaction
   */
  def insert(transactions: Seq[ExtractedTransactionModel]): DBIO[Option[Int]] = this.transactions ++= transactions

  /**
   * exec insert query
   * @param transactions Seq of transaction
   */
  def save(transactions: Seq[ExtractedTransactionModel]): Future[Unit] = {
    db.run(insert(transactions)).map(_ => ())
  }

  /**
   * @param txId transaction id
   * @return whether tx exists or not
   */
  def exists(txId: String): Boolean = {
    val res = db.run(transactions.filter(_.id === txId).exists.result)
    Await.result(res, 5.second)
  }

  /**
   * deletes all txs from db
   */
  def deleteAll(): Unit = {
    val res = db.run(transactions.delete)
    Await.result(res, 5.second)
  }
}
