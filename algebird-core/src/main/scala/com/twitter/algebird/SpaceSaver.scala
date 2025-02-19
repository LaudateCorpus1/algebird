package com.twitter.algebird

import java.nio.ByteBuffer

import scala.collection.immutable.SortedMap
import scala.util.{Failure, Success, Try}

object SpaceSaver {

  /**
   * Construct SpaceSaver with given capacity containing a single item. This is the public api to create a new
   * SpaceSaver.
   */
  def apply[T](capacity: Int, item: T): SpaceSaver[T] = SSOne(capacity, item)

  /**
   * Construct SpaceSaver with given capacity containing a single item with provided exact count. This is the
   * public api to create a new SpaceSaver.
   */
  def apply[T](capacity: Int, item: T, count: Long): SpaceSaver[T] =
    SSMany(capacity, Map(item -> ((count, 0L))))

  private[algebird] val ordering =
    Ordering.by[(_, (Long, Long)), (Long, Long)] { case (_, (count, err)) =>
      (-count, err)
    }

  implicit def spaceSaverSemiGroup[T]: Semigroup[SpaceSaver[T]] =
    new SpaceSaverSemigroup[T]

  /**
   * Encodes the SpaceSaver as a sequence of bytes containing in order
   *   - 1 byte: 1/2 => 1 = SSOne, 2 = SSMany
   *   - 4 bytes: the capacity
   *   - N bytes: the item/counters (counters as length + N*(item size + item + 2 * counters)
   */
  def toBytes[T](ss: SpaceSaver[T], tSerializer: T => Array[Byte]): Array[Byte] =
    ss match {
      case SSOne(capacity, item) =>
        val itemAsBytes = tSerializer(item)
        val itemLength = itemAsBytes.length
        // 1 for the type, 4 for capacity, 4 for itemAsBytes.length
        val buffer = new Array[Byte](1 + 4 + 4 + itemLength)
        ByteBuffer
          .wrap(buffer)
          .put(1: Byte)
          .putInt(capacity)
          .putInt(itemLength)
          .put(itemAsBytes)
        buffer

      case SSMany(
            capacity,
            counters,
            _
          ) => // We do not care about the buckets are thery are created by SSMany.apply
        val buffer = scala.collection.mutable.ArrayBuffer.newBuilder[Byte]
        buffer += (2: Byte)

        var buff = ByteBuffer.allocate(4)
        buff.putInt(capacity)
        buffer ++= buff.array()

        buff = ByteBuffer.allocate(4)
        buff.putInt(counters.size)
        buffer ++= buff.array()
        counters.foreach { case (item, (a, b)) =>
          val itemAsBytes = tSerializer(item)

          buff = ByteBuffer.allocate(4)
          buff.putInt(itemAsBytes.length)
          buffer ++= buff.array()

          buffer ++= itemAsBytes

          buff = ByteBuffer.allocate(8 * 2)
          buff.putLong(a)
          buff.putLong(b)
          buffer ++= buff.array()
        }
        buffer.result.toArray
    }

  // Make sure to be reversible so fromBytes(toBytes(x)) == x
  def fromBytes[T](bytes: Array[Byte], tDeserializer: Array[Byte] => Try[T]): Try[SpaceSaver[T]] =
    fromByteBuffer(ByteBuffer.wrap(bytes), buffer => tDeserializer(buffer.array()))

  def fromByteBuffer[T](bb: ByteBuffer, tDeserializer: ByteBuffer => Try[T]): Try[SpaceSaver[T]] =
    Try {
      bb.get.toInt match {
        case 1 =>
          val capacity = bb.getInt
          val itemLength = bb.getInt
          val itemAsBytes = new Array[Byte](itemLength)
          bb.get(itemAsBytes)
          tDeserializer(ByteBuffer.wrap(itemAsBytes)).map(item => SSOne(capacity, item))
        case 2 =>
          val capacity = bb.getInt

          var countersToDeserialize = bb.getInt
          val counters = scala.collection.mutable.Map.empty[T, (Long, Long)]
          while (countersToDeserialize != 0) {
            val itemLength = bb.getInt()
            val itemAsBytes = new Array[Byte](itemLength)
            bb.get(itemAsBytes)
            val item = tDeserializer(ByteBuffer.wrap(itemAsBytes))

            val a = bb.getLong
            val b = bb.getLong

            item match {
              case Failure(e) => return Failure(e)
              case Success(i) =>
                counters += ((i, (a, b)))
            }

            countersToDeserialize -= 1
          }

          Success(SSMany(capacity, counters.toMap))
      }
    }.flatten
}

/**
 * Data structure used in the Space-Saving Algorithm to find the approximate most frequent and top-k elements.
 * The algorithm is described in "Efficient Computation of Frequent and Top-k Elements in Data Streams". See
 * here: www.cs.ucsb.edu/research/tech_reports/reports/2005-23.pdf In the paper the data structure is called
 * StreamSummary but we chose to call it SpaceSaver instead. Note that the adaptation to hadoop and
 * parallelization were not described in the article and have not been proven to be mathematically correct or
 * preserve the guarantees or benefits of the algorithm.
 */
sealed abstract class SpaceSaver[T] {
  import SpaceSaver.ordering

  /**
   * Maximum number of counters to keep (parameter "m" in the research paper).
   */
  def capacity: Int

  /**
   * Current lowest value for count
   */
  def min: Long

  /**
   * Map of item to counter, where each counter consists of an observed count and possible over-estimation
   * (error)
   */
  def counters: Map[T, (Long, Long)]

  def ++(other: SpaceSaver[T]): SpaceSaver[T]

  /**
   * returns the frequency estimate for the item
   */
  def frequency(item: T): Approximate[Long] = {
    val (count, err) = counters.getOrElse(item, (min, min))
    Approximate(count - err, count, count, 1.0)
  }

  /**
   * Get the elements that show up more than thres times. Returns sorted in descending order: (item,
   * Approximate[Long], guaranteed)
   */
  def mostFrequent(thres: Int): Seq[(T, Approximate[Long], Boolean)] =
    counters.iterator
      .filter { case (_, (count, _)) => count >= thres }
      .toList
      .sorted(ordering)
      .map { case (item, (count, err)) =>
        (item, Approximate(count - err, count, count, 1.0), thres <= count - err)
      }

  /**
   * Get the top-k elements. Returns sorted in descending order: (item, Approximate[Long], guaranteed)
   */
  def topK(k: Int): Seq[(T, Approximate[Long], Boolean)] = {
    require(k < capacity)
    val si = counters.toList
      .sorted(ordering)
    val siK = si.take(k)
    val countKPlus1 = si.drop(k).headOption.map(_._2._1).getOrElse(0L)
    siK.map { case (item, (count, err)) =>
      (item, Approximate(count - err, count, count, 1.0), countKPlus1 < count - err)
    }
  }

  /**
   * Check consistency with other SpaceSaver, useful for testing. Returns boolean indicating if they are
   * consistent
   */
  def consistentWith(that: SpaceSaver[T]): Boolean =
    (counters.keys ++ that.counters.keys).forall(item => (frequency(item) - that.frequency(item)) ~ 0)
}

case class SSOne[T] private[algebird] (override val capacity: Int, item: T) extends SpaceSaver[T] {
  require(capacity > 1)

  override def min: Long = 0L

  override def counters: Map[T, (Long, Long)] = Map(item -> ((1L, 1L)))

  override def ++(other: SpaceSaver[T]): SpaceSaver[T] = other match {
    case other: SSOne[_]  => SSMany(this).add(other)
    case other: SSMany[_] => other.add(this)
  }
}

object SSMany {
  private def bucketsFromCounters[T](counters: Map[T, (Long, Long)]): SortedMap[Long, Set[T]] =
    SortedMap[Long, Set[T]]() ++ counters.groupBy(_._2._1).mapValues(_.keySet).toMap

  private[algebird] def apply[T](capacity: Int, counters: Map[T, (Long, Long)]): SSMany[T] =
    SSMany(capacity, counters, bucketsFromCounters(counters))

  private[algebird] def apply[T](one: SSOne[T]): SSMany[T] =
    SSMany(one.capacity, Map(one.item -> ((1L, 0L))), SortedMap(1L -> Set(one.item)))
}

case class SSMany[T] private (
    override val capacity: Int,
    override val counters: Map[T, (Long, Long)],
    buckets: SortedMap[Long, Set[T]]
) extends SpaceSaver[T] {
  private val exact: Boolean = counters.size < capacity

  override val min: Long = if (counters.size < capacity) 0L else buckets.firstKey

  // item is already present and just needs to be bumped up one
  private def bump(item: T) = {
    val (count, err) = counters(item)
    val counters1 = counters + (item -> ((count + 1L, err))) // increment by one
    val currBucket = buckets(count) // current bucket
    val buckets1 = {
      if (currBucket.size == 1) // delete current bucket since it will be empty
        buckets - count
      else // remove item from current bucket
        buckets + (count -> (currBucket - item))
    } + (count + 1L -> (buckets.getOrElse(count + 1L, Set()) + item))
    SSMany(capacity, counters1, buckets1)
  }

  // lose one item to meet capacity constraint
  private def loseOne = {
    val firstBucket = buckets(buckets.firstKey)
    val itemToLose = firstBucket.head
    val counters1 = counters - itemToLose
    val buckets1 =
      if (firstBucket.size == 1)
        buckets - min
      else
        buckets + (min -> (firstBucket - itemToLose))
    SSMany(capacity, counters1, buckets1)
  }

  // introduce new item
  private def introduce(item: T, count: Long, err: Long) = {
    val counters1 = counters + (item -> ((count, err)))
    val buckets1 = buckets + (count -> (buckets.getOrElse(count, Set()) + item))
    SSMany(capacity, counters1, buckets1)
  }

  // add a single element
  private[algebird] def add(x: SSOne[T]): SSMany[T] = {
    require(x.capacity == capacity)
    if (counters.contains(x.item))
      bump(x.item)
    else
      (if (exact) this else this.loseOne).introduce(x.item, min + 1L, min)
  }

  // merge two stream summaries
  private def merge(x: SSMany[T]): SSMany[T] = {
    require(x.capacity == capacity)
    val counters1 = Map() ++
      (counters.keySet ++ x.counters.keySet).toList
        .map { key =>
          val (count1, err1) = counters.getOrElse(key, (min, min))
          val (count2, err2) = x.counters.getOrElse(key, (x.min, x.min))
          (key -> ((count1 + count2, err1 + err2)))
        }
        .sorted(SpaceSaver.ordering)
        .take(capacity)
    SSMany(capacity, counters1)
  }

  override def ++(other: SpaceSaver[T]): SpaceSaver[T] = other match {
    case other: SSOne[_]  => add(other)
    case other: SSMany[_] => merge(other)
  }
}

class SpaceSaverSemigroup[T] extends Semigroup[SpaceSaver[T]] {
  override def plus(x: SpaceSaver[T], y: SpaceSaver[T]): SpaceSaver[T] = x ++ y
}
