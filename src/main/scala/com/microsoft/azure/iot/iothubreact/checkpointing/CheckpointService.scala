// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iot.iothubreact.checkpointing

import java.time.Instant
import java.util.concurrent.Executors

import akka.actor.{Actor, Stash}
import com.microsoft.azure.iot.iothubreact.Logger
import com.microsoft.azure.iot.iothubreact.checkpointing.CheckpointService.{GetOffset, StoreOffset, UpdateOffset}
import com.microsoft.azure.iot.iothubreact.checkpointing.backends.{AzureBlob, CassandraTable, CheckpointBackend}
import com.microsoft.azure.iot.iothubreact.scaladsl.IoTHubPartition

import scala.concurrent.ExecutionContext

private[iothubreact] object CheckpointService {

  // Command used to read the current partition position
  case object GetOffset

  // Command used to update the position stored in memory
  case class UpdateOffset(value: String)

  // Command use to write the position from memory to storage
  case object StoreOffset

}

/** Checkpointing agent. Takes care of initializing the right storage, reading and writing to it.
  * Each agent instance work on a single IoT hub partition
  *
  * @param partition IoT hub partition number [0..N]
  */
private[iothubreact] class CheckpointService(partition: Int)(implicit config: ICPConfiguration)
  extends Actor
    with Stash
    with Logger {

  type OffsetsData = Tuple3[String, Long, Long]

  implicit val executionContext = ExecutionContext
    .fromExecutorService(Executors.newFixedThreadPool(sys.runtime.availableProcessors))

  // Contains the offsets up to one hour ago, max 1 offset per second (max size = 3600)
  private[this] val queue                     = new scala.collection.mutable.Queue[OffsetsData]
  // Count the offsets tracked in the queue (!= queue.size)
  private[this] var queuedOffsets   : Long    = 0
  private[this] var currentOffset   : String  = IoTHubPartition.OffsetStartOfStream
  private[this] val storage                   = getCheckpointBackend
  private[this] var schedulerStarted: Boolean = false

  override def receive: Receive = notReady

  // At the beginning the actor can only read, stashing other commands for later
  def notReady: Receive = {
    case _ ⇒ {
      try {
        context.become(busyReading)
        stash()
        log.debug(s"Retrieving partition ${partition} offset from the storage")
        val offset = storage.readOffset(partition)
        if (offset != IoTHubPartition.OffsetCheckpointNotFound) {
          currentOffset = offset
        }
        log.debug(s"Offset retrieved for partition ${partition}: ${currentOffset}")
        context.become(ready)
        queuedOffsets = 0
      }
      catch {
        case e: Exception ⇒
          log.error(e, e.getMessage)
          context.become(notReady)
      }
      finally {
        unstashAll()
      }
    }
  }

  // While reading the offset, we stash all commands, to avoid concurrent GetOffset commands
  def busyReading: Receive = {
    case _ ⇒ stash()
  }

  // After loading the offset from the storage, the actor is ready process all commands
  def ready: Receive = {

    case GetOffset ⇒ sender() ! currentOffset

    case UpdateOffset(value: String) ⇒ updateOffsetAction(value)

    case StoreOffset ⇒ {
      try {
        if (queue.size > 0) {
          context.become(busyWriting)

          var offsetToStore: String = ""
          val now = Instant.now.getEpochSecond

          val timeThreshold = config.checkpointTimeThreshold.toSeconds
          val countThreshold = config.checkpointCountThreshold

          // Check if the queue contains old offsets to flush (time threshold)
          // Check if the queue contains data of too many messages (count threshold)
          while (queue.size > 0 && ((queuedOffsets >= countThreshold) || ((now - timeOf(queue.head)) >= timeThreshold))) {
            val data = queue.dequeue()
            offsetToStore = offsetOf(data)
            queuedOffsets -= countOf(data)

            if (queue.size == 0) queuedOffsets = 0
          }

          if (offsetToStore == "") {
            log.debug(s"Checkpoint skipped: partition=${partition}, count ${queuedOffsets} < threshold ${config.checkpointCountThreshold}")
          } else {
            log.info(s"Writing checkpoint: partition=${partition}, storing ${offsetToStore} (current offset=${currentOffset})")
            storage.writeOffset(partition, offsetToStore)
          }
        } else {
          log.debug(s"Partition=${partition}, checkpoint queue is empty [count ${queuedOffsets}, current offset=${currentOffset}]")
        }
      } catch {
        case e: Exception ⇒ log.error(e, e.getMessage)
      } finally {
        context.become(ready)
      }
    }
  }

  // While writing we discard StoreOffset signals
  def busyWriting: Receive = {

    case GetOffset ⇒ sender() ! currentOffset

    case UpdateOffset(value: String) ⇒ updateOffsetAction(value)

    case StoreOffset ⇒ {}
  }

  def updateOffsetAction(offset: String) = {

    if (!schedulerStarted) {
      val time = config.checkpointFrequency
      schedulerStarted = true
      context.system.scheduler.schedule(time, time, self, StoreOffset)
      log.info(s"Scheduled checkpoint for partition ${partition} every ${time.toMillis} ms")
    }

    if (offset.toLong > currentOffset.toLong) {
      val epoch = Instant.now.getEpochSecond

      // Reminder:
      //  queue.enqueue -> queue.last == queue(queue.size -1)
      //  queue.dequeue -> queue.head == queue(0)

      // If the tail of the queue contains an offset stored in the current second, then increment
      // the count of messages for that second. Otherwise enqueue a new element.
      if (queue.size > 0 && epoch == timeOf(queue.last))
        queue.update(queue.size - 1, Tuple3(offset, epoch, countOf(queue.last) + 1))
      else
        queue.enqueue(Tuple3(offset, epoch, 1))

      queuedOffsets += 1
      currentOffset = offset
    }
  }

  // TODO: Support plugins
  def getCheckpointBackend: CheckpointBackend = {
    val conf = config.checkpointBackendType
    conf.toUpperCase match {
      case "AZUREBLOB" ⇒ new AzureBlob
      case "CASSANDRA" ⇒ new CassandraTable
      case _           ⇒ throw new UnsupportedOperationException(s"Unknown storage type ${conf}")
    }
  }

  def offsetOf(x: OffsetsData): String = x._1

  def timeOf(x: OffsetsData): Long = x._2

  def countOf(x: OffsetsData): Long = x._3
}
