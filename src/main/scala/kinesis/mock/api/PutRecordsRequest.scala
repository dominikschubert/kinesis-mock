package kinesis.mock
package api

import java.time.Instant

import cats.Eq
import cats.effect.{IO, Ref}
import cats.syntax.all._
import io.circe

import kinesis.mock.models._
import kinesis.mock.syntax.either._
import kinesis.mock.validations.CommonValidations

final case class PutRecordsRequest(
    records: Vector[PutRecordsRequestEntry],
    streamName: StreamName
) {
  def putRecords(
      streamsRef: Ref[IO, Streams]
  ): IO[Response[PutRecordsResponse]] =
    streamsRef.modify[Response[PutRecordsResponse]] { streams =>
      val now = Instant.now()
      CommonValidations
        .validateStreamName(streamName)
        .flatMap(_ =>
          CommonValidations
            .findStream(streamName, streams)
            .flatMap { stream =>
              (
                CommonValidations.isStreamActiveOrUpdating(streamName, streams),
                records.traverse(x =>
                  (
                    CommonValidations.validatePartitionKey(x.partitionKey),
                    x.explicitHashKey match {
                      case Some(explHashKey) =>
                        CommonValidations.validateExplicitHashKey(explHashKey)
                      case None => Right(())
                    },
                    CommonValidations.validateData(x.data),
                    CommonValidations
                      .computeShard(x.partitionKey, x.explicitHashKey, stream)
                      .flatMap { case (shard, records) =>
                        CommonValidations
                          .isShardOpen(shard)
                          .map(_ => (shard, records))
                      }
                  ).mapN { case (_, _, _, (shard, records)) =>
                    (shard, records, x)
                  }
                )
              ).mapN((_, recs) => (stream, recs))
            }
        )
        .map { case (stream, recs) =>
          val grouped = recs
            .groupBy { case (shard, records, _) =>
              (shard, records)
            }
            .map { case ((shard, records), list) =>
              (shard, records) ->
                list.map(_._3).zipWithIndex.map { case (entry, index) =>
                  val seqNo = SequenceNumber.create(
                    shard.createdAtTimestamp,
                    shard.shardId.index,
                    None,
                    Some(records.length + index),
                    Some(now)
                  )
                  (
                    KinesisRecord(
                      now,
                      entry.data,
                      stream.encryptionType,
                      entry.partitionKey,
                      seqNo
                    ),
                    PutRecordsResultEntry(
                      None,
                      None,
                      Some(seqNo),
                      Some(shard.shardId.shardId)
                    )
                  )
                }
            }
            .toVector

          val newShards = grouped.map {
            case ((shard, currentRecords), recordsToAdd) =>
              (
                shard,
                (
                  currentRecords ++ recordsToAdd.map(_._1),
                  recordsToAdd.map(_._2)
                )
              )
          }

          (
            streams.updateStream(
              stream.copy(
                shards = stream.shards ++ newShards.map {
                  case (shard, (records, _)) => shard -> records
                }
              )
            ),
            PutRecordsResponse(
              stream.encryptionType,
              0,
              newShards.flatMap { case (_, (_, resultEntries)) =>
                resultEntries
              }
            )
          )
        }
        .sequenceWithDefault(streams)
    }
}

object PutRecordsRequest {
  implicit val putRecordsRequestCirceEncoder: circe.Encoder[PutRecordsRequest] =
    circe.Encoder.forProduct2("Records", "StreamName")(x =>
      (x.records, x.streamName)
    )

  implicit val putRecordsRequestCirceDecoder: circe.Decoder[PutRecordsRequest] =
    x =>
      for {
        records <- x.downField("Records").as[Vector[PutRecordsRequestEntry]]
        streamName <- x.downField("StreamName").as[StreamName]
      } yield PutRecordsRequest(records, streamName)

  implicit val putRecordsRequestEncoder: Encoder[PutRecordsRequest] =
    Encoder.derive
  implicit val putRecordsRequestDecoder: Decoder[PutRecordsRequest] =
    Decoder.derive

  implicit val putRecordsRequestEq: Eq[PutRecordsRequest] = (x, y) =>
    x.records === y.records &&
      x.streamName == y.streamName
}
