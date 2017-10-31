package com.github.ldaniels528.trifecta.io.kafka

import java.util.Properties
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.nio.channels.ClosedChannelException
import com.github.ldaniels528.commons.helpers.OptionHelper._
import com.github.ldaniels528.commons.helpers.ResourceHelper._
import com.github.ldaniels528.trifecta.io.ByteBufferUtils._
import com.github.ldaniels528.trifecta.io.IOCounter
import com.github.ldaniels528.trifecta.io.kafka.KafkaMicroConsumer._
import com.github.ldaniels528.trifecta.io.kafka.KafkaZkUtils.{BrokerDetails, ConsumerDetails, ConsumerGroup, ConsumerOffset}
import com.github.ldaniels528.trifecta.io.zookeeper.ZKProxy
import com.github.ldaniels528.trifecta.messages.BinaryMessage
import com.github.ldaniels528.trifecta.messages.logic.Condition
import com.github.ldaniels528.trifecta.messages.query.KQLRestrictions
import kafka.api._
import kafka.common._
import kafka.consumer.SimpleConsumer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * Kafka Low-Level Message Consumer
  * @author lawrence.daniels@gmail.com
  */
class KafkaMicroConsumer(topicAndPartition: TopicAndPartition, seedBrokers: Seq[Broker]) {
  // get the leader, meta data and replica brokers
  val (leader, _, replicas) = getLeaderPartitionMetaDataAndReplicas(topicAndPartition, seedBrokers)
    .getOrElse(throw new VxKafkaTopicException("The leader broker could not be determined", topicAndPartition))

  // generate the client ID
  private val clientID = makeClientID("consumer")

  // get the connection (topic consumer)
  private val consumer = connect(leader, clientID)

  /**
    * Closes the underlying consumer instance
    */
  def close(): Unit = {
    Try(consumer.close())
    ()
  }

  /**
    * Commits an offset for the given consumer group
    */
  def commitOffsets(groupId: String, offset: Long, metadata: String) {
    // create the topic/partition and request information
    val requestInfo = Map(topicAndPartition -> OffsetAndMetadata(offset, metadata, timestamp = System.currentTimeMillis()))

    // submit the request, and retrieve the response
    val request = OffsetCommitRequest(groupId, requestInfo, OffsetRequest.CurrentVersion, correlationId, clientID)
    val response = consumer.commitOffsets(request)

    // retrieve the response code
    for {
      topicMap <- response.commitStatusGroupedByTopic.get(topicAndPartition.topic)
      code <- topicMap.get(topicAndPartition)
    } if (code != 0) {
      throw new VxKafkaCodeException(code)
    }
  }

  /**
    * Returns the earliest or latest offset for the given consumer ID
    * @param consumerId   the given consumer ID
    * @param timeInMillis the given time in milliseconds
    * @return the earliest or latest offset
    */
  def earliestOrLatestOffset(consumerId: Int, timeInMillis: Long): Option[Long] = {
    Option(consumer.earliestOrLatestOffset(topicAndPartition, timeInMillis, consumerId))
  }

  /**
    * Retrieves messages for the given corresponding offsets
    * @param offsets   the given offsets
    * @param fetchSize the fetch size
    * @return the response messages
    */
  def fetch(offsets: Long*)(fetchSize: Int = 65536): Seq[MessageData] = {
    // build the request
    val request = offsets.foldLeft(new FetchRequestBuilder().clientId(clientID)) {
      (builder, offset) =>
        builder.addFetch(topicAndPartition.topic, topicAndPartition.partition, offset, fetchSize)
        builder
    }.build()

    // submit the request, and process the response
    val response = consumer.fetch(request)
    if (response.hasError) throw new VxKafkaCodeException(response.errorCode(topicAndPartition.topic, topicAndPartition.partition))
    else {
      val lastOffset = response.highWatermark(topicAndPartition.topic, topicAndPartition.partition)
      (response.messageSet(topicAndPartition.topic, topicAndPartition.partition) map { msgAndOffset =>
        val key: Array[Byte] = Option(msgAndOffset.message) map (_.key) map toArray getOrElse Array.empty
        val message: Array[Byte] = Option(msgAndOffset.message) map (_.payload) map toArray getOrElse Array.empty
        MessageData(topicAndPartition.partition, msgAndOffset.offset, msgAndOffset.nextOffset, lastOffset, key, message)
      }).toSeq
    }
  }

  /**
    * Retrieves the offset of a consumer group ID
    * @param groupId the given consumer group ID (e.g. myConsumerGroup)
    * @return an option of an offset
    */
  def fetchOffset(groupId: String): Option[Long] = {
    // create the topic/partition and request information
    val requestInfo = Seq(topicAndPartition)

    // submit the request, and retrieve the response
    val request = new OffsetFetchRequest(groupId, requestInfo, OffsetFetchRequest.CurrentVersion, correlationId, clientID)
    val response = consumer.fetchOffsets(request)

    // retrieve the offset(s)
    for {
      topicMap <- response.requestInfoGroupedByTopic.get(topicAndPartition.topic)
      ome <- topicMap.get(topicAndPartition)
    } yield ome.offset
  }

  /**
    * Returns the first available offset
    * @return an option of an offset
    */
  def getFirstOffset: Option[Long] = getOffsetsBefore(OffsetRequest.EarliestTime).headOption

  /**
    * Returns the last available offset
    * @return an option of an offset
    */
  def getLastOffset: Option[Long] = getOffsetsBefore(OffsetRequest.LatestTime).headOption

  /**
    * Returns the latest offsets
    * @return an option of an offset
    */
  def getLatestOffsets: Seq[Long] = getOffsetsBefore(OffsetRequest.LatestTime)

  /**
    * Returns the offset for an instance in time
    * @param time the given time EPOC in milliseconds
    * @return an option of an offset
    */
  def getOffsetsBefore(time: Long): Seq[Long] = {
    // create the topic/partition and request information
    val requestInfo = Map(topicAndPartition -> PartitionOffsetRequestInfo(time, 1))
    val replicaId = replicas.indexOf(leader)

    // submit the request, and retrieve the response
    val request = new OffsetRequest(requestInfo, correlationId, replicaId)
    val response = consumer.getOffsetsBefore(request)

    // handle the response
    if (response.hasError) {
      response.partitionErrorAndOffsets map {
        case (_, por) => throw new VxKafkaCodeException(por.error)
      }
      Nil
    } else (for {
      topicMap <- response.offsetsGroupedByTopic.get(topicAndPartition.topic)
      por <- topicMap.get(topicAndPartition)
    } yield por.offsets) getOrElse Nil
  }

}

/**
  * Verify Kafka Message Subscriber Singleton
  * @author lawrence.daniels@gmail.com
  */
object KafkaMicroConsumer {
  private lazy val logger = LoggerFactory.getLogger(getClass)
  private val correlationIdGen = new AtomicInteger(-1)

  val DEFAULT_FETCH_SIZE: Int = 1024*1024 // 1MB
  val kafkaUtil = new KafkaZkUtils(rootKafkaPath = "/")

  /**
    * Returns the next correlation ID
    * @return the next correlation ID
    */
  def correlationId: Int = correlationIdGen.incrementAndGet()

  /**
    * Returns the promise of the total number of a messages that match the given search criteria
    * @param topic      the given topic name
    * @param brokers    the given replica brokers
    * @param conditions the given search criteria
    * @return the promise of the total number of messages that match the given search criteria
    */
  def count(topic: String, brokers: Seq[Broker], conditions: Condition*)(implicit ec: ExecutionContext, zk: ZKProxy): Future[Long] = {
    val tasks = getTopicPartitions(topic) map { partition =>
      Future.successful {
        var counter = 0L
        new KafkaMicroConsumer(TopicAndPartition(topic, partition), brokers) use { subs =>
          var offset: Option[Long] = subs.getFirstOffset
          val lastOffset: Option[Long] = subs.getLastOffset

          def eof: Boolean = offset.exists(o => lastOffset.exists(o > _))

          while (!eof) {
            for {
              ofs <- offset
              msg <- subs.fetch(ofs)(DEFAULT_FETCH_SIZE)
            } if (conditions.forall(_.satisfies(msg.message, msg.key))) counter += 1
            offset = offset map (_ + 1)
          }
        }
        counter
      }
    }

    // return the summed count
    Future.sequence(tasks).map(_.sum)
  }

  /**
    * Returns the promise of the option of a message based on the given search criteria
    * @param topic         the given topic name
    * @param brokers       the given replica brokers
    * @param correlationId the given correlation ID
    * @param conditions    the given search [[Condition criteria]]
    * @param restrictions  the given [[KQLRestrictions restrictions]]
    * @param limit         the maximum number of results to return
    * @param counter       the given [[IOCounter I/O counter]]
    * @return the promise of a collection of [[MessageData messages]] based on the given search criteria
    */
  def findMany(topic: String,
               brokers: Seq[Broker],
               correlationId: Int,
               conditions: Seq[Condition],
               restrictions: KQLRestrictions,
               limit: Option[Int],
               counter: IOCounter)(implicit ec: ExecutionContext, zk: ZKProxy): Future[Seq[MessageData]] = {
    val count = new AtomicLong(0L)
    val partitions = getTopicPartitions(topic).distinct
    val tasks: Future[Seq[MessageData]] = Future.sequence {
      partitions map { partition =>
        Future {
          var matches: List[MessageData] = Nil
          new KafkaMicroConsumer(TopicAndPartition(topic, partition), brokers) use { subs =>
            var offset: Option[Long] = subs.getStartingOffset(restrictions)
            val lastOffset: Option[Long] = subs.getLastOffset
            while (offset.exists(o => lastOffset.exists(o < _)) && (limit.isEmpty || limit.exists(count.get < _))) {
              val messages_? = offset.map(ofs => subs.fetch(ofs)(DEFAULT_FETCH_SIZE))

              // process the messages, then point to the next available offset
              messages_? foreach { mds =>
                counter.updateReadCount(mds.length)
                mds foreach { md =>
                  if (conditions.forall(_.satisfies(md.message, md.key))) {
                    matches = md :: matches
                    count.incrementAndGet()
                  }
                }
              }
              offset = getNextOffset(offset, messages_?)
            }
            matches
          }
        }
      }
    } map (_.flatten)

    // return a promise of the messages
    val sortedMessages = tasks.map(_.sortBy(_.partition))
    limit.map(n => sortedMessages.map(_.take(n))) getOrElse sortedMessages
  }

  /**
    * Returns the promise of the option of a message based on the given search criteria
    * @param topic      the given topic name
    * @param brokers    the given replica brokers
    * @param conditions the given search criteria
    * @return the promise of the option of a message based on the given search criteria
    */
  def findOne(topic: String, brokers: Seq[Broker], conditions: Condition*)(implicit ec: ExecutionContext, zk: ZKProxy): Future[Option[(Int, MessageData)]] = {
    val message = new AtomicReference[Option[(Int, MessageData)]](None)
    val tasks = getTopicPartitions(topic) map { partition =>
      Future {
        new KafkaMicroConsumer(TopicAndPartition(topic, partition), brokers) use { subs =>
          var offset: Option[Long] = subs.getFirstOffset
          val lastOffset: Option[Long] = subs.getLastOffset

          // while no message was selected and the offset below the limit ...
          while (message.get().isEmpty && offset.exists(o => lastOffset.exists(o < _))) {
            val messages_? = offset.map(ofs => subs.fetch(ofs)(DEFAULT_FETCH_SIZE))
            for {
              messages <- messages_?
              msg <- messages if message.get().isEmpty
            } {
              if (conditions.forall(_.satisfies(msg.message, msg.key))) {
                message.compareAndSet(None, Option(partition -> msg))
              }
            }
            offset = getNextOffset(offset, messages_?)
          }
        }
      }
    }

    // return the message
    Future.sequence(tasks) map (_ => message.get)
  }

  private def getNextOffset(offset_? : Option[Long], messages_? : Option[Seq[MessageData]]): Option[Long] = {
    messages_? match {
      case None => None
      case Some(mds) if mds.isEmpty => offset_?.map(_ + 1)
      case Some(mds) => Option(mds.map(_.offset).max + 1)
    }
  }

  private def getPreviousOffset(messages_? : Option[Seq[MessageData]]): Option[Long] = {
    messages_? match {
      case None => None
      case Some(mds) if mds.isEmpty => None
      case Some(mds) => Option(mds.map(_.offset).min - 1)
    }
  }

  /**
    * Returns the promise of the option of a message based on the given search criteria
    * @param tap        the given [[TopicAndPartition]]
    * @param brokers    the given replica brokers
    * @param conditions the given search criteria
    * @return the promise of the option of a message based on the given search criteria
    */
  def findNext(tap: TopicAndPartition, brokers: Seq[Broker], conditions: Condition*)(implicit ec: ExecutionContext, zk: ZKProxy): Future[Option[MessageData]] = {
    val message = new AtomicReference[Option[MessageData]](None)
    Future.successful {
      new KafkaMicroConsumer(tap, brokers) use { subs =>
        var offset: Option[Long] = subs.getFirstOffset
        // TODO this should be supplied
        val lastOffset: Option[Long] = subs.getLastOffset

        while (message.get().isEmpty && offset.exists(o => lastOffset.exists(o < _))) {
          val messages_? = offset.map(ofs => subs.fetch(ofs)(DEFAULT_FETCH_SIZE))
          for {
            messages <- messages_?
            msg <- messages if message.get().isEmpty
          } {
            if (conditions.forall(_.satisfies(msg.message, msg.key))) {
              message.compareAndSet(None, Option(msg))
            }
          }
          offset = getNextOffset(offset, messages_?)
        }
      }
      message.get
    }
  }

  /**
    * Retrieves the list of bootstrap servers as a comma separated string
    */
  def getBootstrapServers(implicit zk: ZKProxy): String = kafkaUtil.getBootstrapServers

  /**
    * Retrieves the list of defined brokers from Zookeeper
    */
  def getBrokerList(implicit zk: ZKProxy): Seq[BrokerDetails] = kafkaUtil.getBrokerList

  def getBrokers(implicit zk: ZKProxy): Seq[Broker] = {
    kafkaUtil.getBrokerList map (b => Broker(b.host, b.port))
  }

  /**
    * Retrieves the list of internal consumers from Kafka (Play version)
    */
  def getConsumerGroupsFromKafka(groupIds: Seq[String], autoOffsetReset: String)(implicit zk: ZKProxy): Seq[ConsumerGroup] = {
    val topicList = KafkaMicroConsumer.getTopicList(KafkaMicroConsumer.getBrokers)
    val topicPartitions = topicList.map(t => new TopicPartition(t.topic, t.partitionId)).asJavaCollection
    val topics = topicList.map(_.topic).distinct

    groupIds flatMap { groupId =>
      val props = new Properties()
      props.put("bootstrap.servers", KafkaMicroConsumer.getBootstrapServers)
      props.put("group.id", groupId)
      props.put("auto.offset.reset", autoOffsetReset)
      props.put("key.deserializer", classOf[StringDeserializer].getName)
      props.put("value.deserializer", classOf[StringDeserializer].getName)

      // lookup the owners and consumer threads
      val owners = Try(KafkaMicroConsumer.kafkaUtil.getConsumerOwners(groupId)).toOption
      val threads = Try(KafkaMicroConsumer.kafkaUtil.getConsumerThreads(groupId)).toOption

      new KafkaConsumer[Array[Byte], Array[Byte]](props) use { consumer =>
        consumer.subscribe(topics)
        consumer.poll(0)
        topicPartitions flatMap { tp =>
          Try(consumer.position(tp)) match {
            case Success(offset) =>
              val thread = threads.flatMap(_.find(t => t.topic == tp.topic()))
              val lastModified = thread.flatMap(t => Try(t.timestamp.toLong).toOption)
              Some(ConsumerGroup(
                consumerId = groupId,
                offsets = Seq(ConsumerOffset(groupId = groupId, topic = tp.topic(), partition = tp.partition(), offset = offset, lastModifiedTime = lastModified)),
                owners = owners getOrElse Nil,
                threads = threads getOrElse Nil
              ))
            case Failure(e) =>
              logger.error("Failed to retrieve Kafka consumers", e)
              None
          }
        }
      }
    }
  }

  /**
    * Retrieves the list of internal consumers from Kafka
    * TODO consolidate with Play version and remove this method
    */
  def getConsumersFromKafka(groupIds: Seq[String], autoOffsetReset: String)(implicit zk: ZKProxy): Seq[ConsumerDetails] = {
    val topicList = getTopicList(getBrokers)
    val topicPartitions = topicList.map(t => new TopicPartition(t.topic, t.partitionId)).asJavaCollection
    val topics = topicList map (_.topic) distinct
    val bootstrapServers = KafkaMicroConsumer.getBootstrapServers

    groupIds flatMap { groupId =>
      val props = new Properties()
      props.put("bootstrap.servers", bootstrapServers)
      props.put("group.id", groupId)
      props.put("auto.offset.reset", autoOffsetReset)
      props.put("key.deserializer", classOf[StringDeserializer].getName)
      props.put("value.deserializer", classOf[StringDeserializer].getName)

      // lookup the owners and consumer threads
      val owners = Try(kafkaUtil.getConsumerOwners(groupId)).toOption
      val threads = Try(kafkaUtil.getConsumerThreads(groupId)).toOption

      new KafkaConsumer[Array[Byte], Array[Byte]](props) use { consumer =>
        consumer.subscribe(topics)
        consumer.poll(0)
        topicPartitions flatMap { tp =>
          Try(consumer.position(tp)) match {
            case Success(offset) =>
              val owner = owners.flatMap(_.find(o => o.topic == tp.topic() && o.partition == tp.partition()))
              val thread = threads.flatMap(_.find(t => t.topic == tp.topic()))
              Some(ConsumerDetails(
                version = thread.map(_.version),
                groupId,
                owner.map(_.threadId),
                tp.topic(),
                tp.partition(),
                offset,
                lastModified = thread.flatMap(t => Try(t.timestamp.toLong).toOption),
                lastModifiedISO = thread.flatMap(_.timestampISO)))
            case Failure(e) =>
              logger.error("Failed to retrieve Kafka consumers", e)
              None
          }
        }
      }
    }
  }

  /**
    * Retrieves the list of internal consumers' offsets from Kafka (Play version)
    */
  def getConsumerGroupOffsetsFromKafka(groupIds: Seq[String], autoOffsetReset: String)(implicit zk: ZKProxy): Seq[ConsumerOffset] = {
    val brokers = KafkaMicroConsumer.getBrokers
    val topicList = KafkaMicroConsumer.getTopicList(brokers)
    val topicPartitions = topicList.map(t => new TopicPartition(t.topic, t.partitionId)).asJavaCollection
    val topics = topicList.map(_.topic).distinct

    groupIds flatMap { groupId =>
      val props = KafkaConfigGenerator.getConsumerProperties(brokers = getBrokerList, groupId, Some(autoOffsetReset))

      // lookup the consumer threads
      val threads = Try(KafkaMicroConsumer.kafkaUtil.getConsumerThreads(groupId)).toOption

      new KafkaConsumer[Array[Byte], Array[Byte]](props) use { consumer =>
        consumer.subscribe(topics)
        consumer.poll(0)
        topicPartitions flatMap { tp =>
          Try(consumer.position(tp)) match {
            case Success(offset) =>
              val thread = threads.flatMap(_.find(t => t.topic == tp.topic()))
              val lastModified = thread.flatMap(t => Try(t.timestamp.toLong).toOption)
              Some(ConsumerOffset(groupId = groupId, topic = tp.topic(), partition = tp.partition(), offset = offset, lastModifiedTime = lastModified))
            case Failure(e) =>
              logger.error("Failed to retrieve Kafka consumers", e)
              None
          }
        }
      }
    }
  }

  /**
    * Retrieves the list of consumers from Zookeeper
    * TODO consolidate with Play version and remove this method
    */
  def getConsumerFromZookeeper(topicPrefix: Option[String] = None)(implicit zk: ZKProxy): Seq[ConsumerDetails] = {
    kafkaUtil.getConsumerDetails filter (cd => contentFilter(topicPrefix, cd.topic))
  }

  /**
    * Retrieves the list of consumers from Zookeeper (Kafka-Storm Partition Manager Version)
    */
  def getConsumersForStorm()(implicit zk: ZKProxy): Seq[ConsumerDetailsPM] = kafkaUtil.getConsumersForStorm()

  def getReplicas(topic: String, brokers: Seq[Broker])(implicit zk: ZKProxy): Seq[ReplicaBroker] = {
    logger.warn(s"getReplicas called ${topic}")
    val results = for {
      partition <- getTopicPartitions(topic)
      (leader, pmd, replicas) <- getLeaderPartitionMetaDataAndReplicas(TopicAndPartition(topic, partition), brokers)
      inSyncReplicas = pmd.isr map (r => Broker(r.host, r.port, r.id))
    } yield (partition, replicas, inSyncReplicas)

    results flatMap { case (partition, replicas, insSyncReplicas) => replicas map (r =>
      ReplicaBroker(partition, r.host, r.port, r.brokerId, insSyncReplicas.contains(r)))
    }
  }

  /**
    * Convenience method for filtering content (consumers, topics, etc.) by a prefix
    * @param prefix the given prefix
    * @param entity the given entity to filter
    * @return true, if the topic starts with the topic prefix
    */
  def contentFilter(prefix: Option[String], entity: String): Boolean = {
    prefix.isEmpty || prefix.exists(entity.startsWith)
  }

  /**
    * Returns the list of partitions for the given topic
    */
  def getTopicPartitions(topic: String)(implicit zk: ZKProxy): Seq[Int] = {
    kafkaUtil.getBrokerTopicPartitions(topic)
  }

  /**
    * Returns the list of topics for the given brokers
    */
  def getTopicList(brokers: Seq[Broker])(implicit zk: ZKProxy): Seq[TopicDetails] = {
    // get the list of topics
    val topics = kafkaUtil.getBrokerTopicNames filterNot (_ == "__consumer_offsets")

    // capture the meta data for all topics
    var my_it=brokers.toIterator
    var my_br:com.github.ldaniels528.trifecta.io.kafka.Broker=null
    while (my_it.hasNext  && my_br==null) {
      my_br = my_it.next()
      try {
        getTopicMetadata(my_br, topics)
      }catch {
        case ex: Exception => {
          logger.error("Couldnt connect System ")
          my_br=null
        }
      }
    }
    if (my_br==null) {
      logger.error("getTopicList- No broker found")
      return Nil
    }

       var data =
          Try (getTopicMetadata(my_br, topics) flatMap { tmd =>
          // check for errors
          if (tmd.errorCode != 0) {
              logger.warn(s"Could not read topic ${tmd.topic}, error: ${tmd.errorCode}")
            None
          } else {
            // translate the partition meta data into topic information instances
            tmd.partitionsMetadata flatMap {
              case pmd if (pmd.errorCode != 0 && pmd.errorCode != 9) =>
                logger.warn(s"Could not read partition ${tmd.topic}/${pmd.partitionId}, error: ${pmd.errorCode}")
                None
              case pmd =>
                Some(TopicDetails(
                  tmd.topic,
                  pmd.partitionId,
                  pmd.leader map (b => Broker(b.host, b.port, b.id)),
                  pmd.replicas map (b => Broker(b.host, b.port, b.id)),
                  pmd.isr map (b => Broker(b.host, b.port, b.id)),
                  tmd.sizeInBytes))
            }
          }
        }).recoverWith({
            case (ex: Throwable) => logger.warn("Couldnt connect System "); Failure(ex);
          }).getOrElse(Nil)

      return data

  }

  /**
    * Returns the list of summarized topics for the given brokers
    */
  def getTopicSummaryList(brokers: Seq[Broker])(implicit zk: ZKProxy): Iterable[TopicSummary] = {
    getTopicList(brokers) groupBy (_.topic) map { case (name, partitions) =>
      TopicSummary(name, partitions.map(_.partitionId).max)
    }
  }

  /**
    * Returns the promise of the option of a message based on the given search criteria
    * @param topic    the given topic name
    * @param brokers  the given replica brokers
    * @param observer the given callback function
    * @return the promise of the option of a message based on the given search criteria
    */
  def observe(topic: String, brokers: Seq[Broker])(observer: MessageData => Unit)(implicit ec: ExecutionContext, zk: ZKProxy): Future[Seq[Unit]] = {
    Future.sequence(getTopicPartitions(topic) map { partition =>
      Future {
        new KafkaMicroConsumer(TopicAndPartition(topic, partition), brokers) use { subs =>
          var offset: Option[Long] = subs.getFirstOffset
          val lastOffset: Option[Long] = subs.getLastOffset

          while (offset.exists(o => lastOffset.exists(o < _))) {
            val messages_? = offset.map(ofs => subs.fetch(ofs)(DEFAULT_FETCH_SIZE))
            for {
              messages <- messages_?
              md <- messages
            } observer(md)
            offset = getNextOffset(offset, messages_?)
          }
        }
      }
    })
  }

  /**
    * Establishes a connection with the specified broker
    * @param broker the specified { @link Broker broker}
    */
  private def connect(broker: Broker, clientID: String): SimpleConsumer = {
      new SimpleConsumer(broker.host, broker.port, DEFAULT_FETCH_SIZE, 63356, clientID)
  }

  /**
    * Retrieves the partition meta data and replicas for the lead broker
    */
  private def getLeaderPartitionMetaDataAndReplicas(tap: TopicAndPartition, brokers: Seq[Broker]): Option[(Broker, PartitionMetadata, Seq[Broker])] = {

    var my_it = brokers.toIterator
    var my_broker:com.github.ldaniels528.trifecta.io.kafka.Broker = null
    var my_consumer:kafka.consumer.SimpleConsumer=null


    while (my_it.hasNext && my_broker==null) {
      try {
        my_broker = my_it.next()
        my_consumer=connect(my_broker,makeClientID("Test_con"))
        getPartitionMetadata(my_broker, tap)

      }catch {
        case ex: Exception => {
          logger.error("Couldnt connect System ")
          my_broker=null
        }
      }
      Try(my_consumer.close())
      ()
    }

    for {

       // pmd <- brokers.foldLeft[Option[PartitionMetadata]](None)((result, broker) =>
      //     result ?? getPartitionMetadata(broker, tap).headOption)
      pmd <- getPartitionMetadata(my_broker, tap).headOption
      leader <- pmd.leader map (r => Broker(r.host, r.port, r.id))
      replicas = pmd.replicas map (r => Broker(r.host, r.port, r.id))
    } yield (leader, pmd, replicas)
  }

  /**
    * Retrieves the partition meta data for the given broker
    */
  private def getPartitionMetadata(broker: Broker, tap: TopicAndPartition): Seq[PartitionMetadata] = {
    connect(broker, makeClientID("pmd_lookup")) use { consumer =>
      Try {

        consumer
          .send(new TopicMetadataRequest(Seq(tap.topic), correlationId))
          .topicsMetadata
          .flatMap(_.partitionsMetadata.find(_.partitionId == tap.partition))

      } match {
        case Success(pmdSeq) =>
         // logger.warn(s"Returned data from partitioneddata ${pmdSeq}")
          pmdSeq
        case Failure(e) =>
          throw new VxKafkaTopicException(s"Error communicating with Broker [$broker] to find Leader", tap, e)

      }
    }

  }

  /**
    * Retrieves the partition meta data for the given broker
    */
  private def getTopicMetadata(broker: Broker, topics: Seq[String]): Seq[TopicMetadata] = {
    connect(broker, makeClientID("tmd_lookup")) use { consumer =>
      Try {
        consumer
          .send(new TopicMetadataRequest(topics, correlationId))
          .topicsMetadata

      } match {
        case Success(tmdSeq) => tmdSeq
        case Failure(e) =>
          throw new VxKafkaException(s"Error communicating with Broker [$broker] Reason: ${e.getMessage}", e)
      }
    }
  }

  /**
    * Generates a unique client identifier
    * @param prefix the given prefix
    * @return a unique client identifier
    */
  private def makeClientID(prefix: String): String = s"$prefix${System.nanoTime()}"

  /**
    * Represents the consumer group details for a given topic partition (Kafka Spout / Partition Manager)
    */
  case class ConsumerDetailsPM(topologyId: String, topologyName: String, topic: String, partition: Int, offset: Long, lastModified: Option[Long], broker: String) {
    lazy val lastModifiedISO: Option[String] = lastModified.flatMap(KafkaZkUtils.toISODateTime(_).toOption)
  }

  /**
    * Represents a message and offset
    * @param offset     the offset of the message within the topic partition
    * @param nextOffset the next available offset
    * @param message    the message
    */
  case class MessageData(partition: Int, offset: Long, nextOffset: Long, lastOffset: Long, key: Array[Byte], message: Array[Byte])
    extends BinaryMessage

  case class ReplicaBroker(partition: Int, host: String, port: Int, id: Int, inSync: Boolean)

  /**
    * Represents the details for a Kafka topic
    */
  case class TopicDetails(topic: String, partitionId: Int, leader: Option[Broker], replicas: Seq[Broker], isr: Seq[Broker], sizeInBytes: Int)

  case class TopicSummary(topic: String, partitions: Int)

  /**
    * Represents a class of exceptions that occur while attempting to fetch data from a Kafka broker
    * @param message the given error message
    * @param cause   the given root cause of the exception
    */
  class VxKafkaException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

  class KafkaConnectionException(message: String, cause: Throwable = null)
    extends Exception(message, cause)

  /**
    * Represents a class of exceptions that occur while attempting to fetch data from a Kafka broker
    * @param code the status/error code
    */
  class VxKafkaCodeException(val code: Short)
    extends VxKafkaException(ERROR_CODES.getOrElse(code, "Unrecognized Error Code"))

  /**
    * Represents a class of exceptions that occur while consuming a Kafka message
    * @param message the given error message
    */
  class VxKafkaTopicException(message: String, tap: TopicAndPartition, cause: Throwable = null)
    extends VxKafkaException(s"$message for topic ${tap.topic} partition ${tap.partition}", cause)

  import kafka.common.ErrorMapping._

  /**
    * Kafka Error Codes
    * @see https://cwiki.apache.org/confluence/display/KAFKA/A+Guide+To+The+Kafka+Protocol
    */
  val ERROR_CODES = Map(
    BrokerNotAvailableCode -> "Broker Not Available",
    InvalidFetchSizeCode -> "Invalid Fetch Size",
    InvalidMessageCode -> "Invalid Message",
    LeaderNotAvailableCode -> "Leader Not Available",
    MessageSizeTooLargeCode -> "Message Size Too Large",
    NoError -> "No Error",
    NotLeaderForPartitionCode -> "Not Leader For Partition",
    OffsetMetadataTooLargeCode -> "Offset Metadata Too Large",
    OffsetOutOfRangeCode -> "Offset Out Of Range",
    ReplicaNotAvailableCode -> "Replica Not Available",
    RequestTimedOutCode -> "Request Timed Out",
    StaleControllerEpochCode -> "Stale Controller Epoch",
    StaleLeaderEpochCode -> "Stale Leader Epoch",
    UnknownCode -> "Unknown Code",
    UnknownTopicOrPartitionCode -> "Unknown Topic-Or-Partition")

  /**
    * Kafka Micro-Consumer Enrichment
    * @param subs the given [[KafkaMicroConsumer subscriber]]
    */
  implicit class KafkaMicroConsumerEnrichment(val subs: KafkaMicroConsumer) extends AnyVal {

    def getStartingOffset(restrictions: KQLRestrictions): Option[Long] = {
      val minimumOffset = subs.getFirstOffset.map(Math.max(0L, _))

      // was a consumer group specified?
      val consumerOffset = for {
        groupId <- restrictions.groupId
        offset <- subs.fetchOffset(groupId)
        safeOffset <- if (offset == -1) minimumOffset else Some(offset)
      } yield safeOffset

      // get the base offset
      val baseOffset = consumerOffset ?? minimumOffset

      // was an offset delta specified?
      val adjustedOffset = for {
        minimum <- minimumOffset
        offset <- baseOffset
        delta <- restrictions.delta
      } yield Math.max(minimum, offset - delta)

      // use either the adjusted offset or the base offset
      adjustedOffset ?? baseOffset
    }
  }

}
