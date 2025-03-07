/*
 * Copyright 2020 Eike K. & Contributors
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package docspell.ftssolr

import cats.effect._
import cats.implicits._
import fs2.Stream

import docspell.common._
import docspell.ftsclient._

import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.log4s.getLogger

final class SolrFtsClient[F[_]: Async](
    solrUpdate: SolrUpdate[F],
    solrSetup: SolrSetup[F],
    solrQuery: SolrQuery[F]
) extends FtsClient[F] {

  def initialize: F[List[FtsMigration[F]]] =
    solrSetup.remainingSetup.map(_.map(_.value))

  def initializeNew: List[FtsMigration[F]] =
    solrSetup.setupSchema.map(_.value)

  def search(q: FtsQuery): F[FtsResult] =
    solrQuery.query(q)

  def indexData(logger: Logger[F], data: Stream[F, TextData]): F[Unit] =
    modifyIndex(logger, data)(solrUpdate.add)

  def updateIndex(logger: Logger[F], data: Stream[F, TextData]): F[Unit] =
    modifyIndex(logger, data)(solrUpdate.update)

  def updateFolder(
      logger: Logger[F],
      itemId: Ident,
      collective: Ident,
      folder: Option[Ident]
  ): F[Unit] =
    logger.debug(
      s"Update folder in solr index for coll/item ${collective.id}/${itemId.id}"
    ) *>
      solrUpdate.updateFolder(itemId, collective, folder)

  def modifyIndex(logger: Logger[F], data: Stream[F, TextData])(
      f: List[TextData] => F[Unit]
  ): F[Unit] =
    (for {
      _ <- Stream.eval(logger.debug("Updating SOLR index"))
      chunks <- data.chunks
      res <- Stream.eval(f(chunks.toList).attempt)
      _ <- res match {
        case Right(()) => Stream.emit(())
        case Left(ex) =>
          Stream.eval(logger.error(ex)("Error updating with chunk of data"))
      }
    } yield ()).compile.drain

  def removeItem(logger: Logger[F], itemId: Ident): F[Unit] =
    logger.debug(s"Remove item '${itemId.id}' from index") *>
      solrUpdate.delete(s"${Field.itemId.name}:${itemId.id}", None)

  def removeAttachment(logger: Logger[F], attachId: Ident): F[Unit] =
    logger.debug(s"Remove attachment '${attachId.id}' from index") *>
      solrUpdate.delete(s"${Field.attachmentId.name}:${attachId.id}", None)

  def clearAll(logger: Logger[F]): F[Unit] =
    logger.info("Deleting complete full-text index!") *>
      solrUpdate.delete("*:*", Option(0))

  def clear(logger: Logger[F], collective: Ident): F[Unit] =
    logger.info(s"Deleting full-text index for collective ${collective.id}") *>
      solrUpdate.delete(s"${Field.collectiveId.name}:${collective.id}", Option(0))
}

object SolrFtsClient {
  private[this] val logger = getLogger

  def apply[F[_]: Async](
      cfg: SolrConfig,
      httpClient: Client[F]
  ): Resource[F, FtsClient[F]] = {
    val client = loggingMiddleware(cfg, httpClient)
    Resource.pure[F, FtsClient[F]](
      new SolrFtsClient(
        SolrUpdate(cfg, client),
        SolrSetup(cfg, client),
        SolrQuery(cfg, client)
      )
    )
  }

  private def loggingMiddleware[F[_]: Async](
      cfg: SolrConfig,
      client: Client[F]
  ): Client[F] =
    Logger(
      logHeaders = true,
      logBody = cfg.logVerbose,
      logAction = Some((msg: String) => Sync[F].delay(logger.trace(msg)))
    )(client)

}
