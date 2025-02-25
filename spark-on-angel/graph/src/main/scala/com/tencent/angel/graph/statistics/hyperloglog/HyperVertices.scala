package com.tencent.angel.graph.statistics.hyperloglog

import java.util.Collections
import com.tencent.angel.graph.utils.params._
import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus
import com.tencent.angel.graph.common.param.ModelContext
import com.tencent.angel.graph.utils.GraphIO
import com.tencent.angel.psagent.PSAgentContext
import com.tencent.angel.spark.context.PSContext
import org.apache.spark.SparkContext
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.{BooleanParam, IntParam, ParamMap}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Dataset, Row}


class HyperVertices(override val uid: String) extends Transformer with HasStorageLevel with HasSrcNodeIdCol
  with HasDstNodeIdCol with HasPSPartitionNum with HasUseBalancePartition with HasBalancePartitionPercent
  with HasPartitionNum with HasOutputNodeIdCol with HasVertexANFCol with HasCardDiffCol with HasSaveCounter
  with HasCounterCol {

  final val p = new IntParam(this, "p", "p")
  final val sp = new IntParam(this, "sp", "sp")
  final val maxIter = new IntParam(this, "maxIter", "maxIter")
  final val msgNumBatch = new IntParam(this, "msgBatchSize", "msgBatchSize")
  final val verboseSaving = new BooleanParam(this, "verboseSaving", "verboseSaving")
  final val isDirected = new BooleanParam(this, "isDirected", "isDirected")
  final val isInDegree = new BooleanParam(this, "isInDegree", "isInDegree")
  final val withVertexType = new BooleanParam(this, "withVertexType", "withVertexType")
  final val vertexIndex = new IntParam(this, "vertexIndex", "vertexIndex")
  final val typeIndex = new IntParam(this, "typeIndex", "typeIndex")

  final def setP(precision: Int): this.type = set(p, precision)

  final def setSp(precision: Int): this.type = set(sp, precision)

  final def setMaxIter(iter: Int): this.type = set(maxIter, iter)

  final def setMsgNumBatch(size: Int): this.type = set(msgNumBatch, size)

  final def setVerboseSaving(verbose: Boolean): this.type = set(verboseSaving, verbose)

  final def setIsDirected(directed: Boolean): this.type = set(isDirected, directed)

  final def setIsInDegree(inDegree: Boolean): this.type = set(isInDegree, inDegree)

  final def setWithVertexType(nodeType: Boolean): this.type = set(withVertexType, nodeType)

  final def setVertexIndex(vIndex: Int): this.type = set(vertexIndex, vIndex)

  final def setTypeIndex(tIndex: Int): this.type = set(typeIndex, tIndex)

  setDefault(p, 6)
  setDefault(sp, 0)
  setDefault(maxIter, 200)
  setDefault(msgNumBatch, 4)
  setDefault(verboseSaving, false)
  setDefault(isDirected, true)
  setDefault(balancePartitionPercent, 0.5f)
  setDefault(isInDegree, true)
  setDefault(withVertexType, false)
  setDefault(vertexIndex, 0)
  setDefault(typeIndex, 1)

  def this() = this(Identifiable.randomUID("hyperVertices"))

  def transform(dataset: Dataset[_], datatype: Dataset[_], tags: Set[String], output: String): Unit = {
    val iniEdges =
      if ($(isDirected)) {
        if ($(isInDegree)) {
          dataset.select($(srcNodeIdCol), $(dstNodeIdCol)).rdd
            .filter(row => !row.anyNull)
            .map(row => (row.getLong(0), row.getLong(1)))
            .filter(f => f._1 != f._2)
        } else {
          dataset.select($(srcNodeIdCol), $(dstNodeIdCol)).rdd
            .filter(row => !row.anyNull)
            .map(row => (row.getLong(1), row.getLong(0)))
            .filter(f => f._1 != f._2)
        }
      } else {
        dataset.select($(srcNodeIdCol), $(dstNodeIdCol)).rdd
          .filter(row => !row.anyNull)
          .flatMap(row => Iterator((row.getLong(0), row.getLong(1)), (row.getLong(1), row.getLong(0))))
          .filter(f => f._1 != f._2)
      }
    iniEdges.persist($(storageLevel))

    val index = iniEdges.flatMap(f => Array(f._1, f._2))

    val edgesOnly = index.distinct().repartition($(partitionNum))
    edgesOnly.persist($(storageLevel))
    edgesOnly.count()

    val (minId, maxId, numEdges) = iniEdges.mapPartitions(summarizeApplyOp).reduce(summarizeReduceOp)

    println(s"minId=$minId maxId=$maxId numEdges=$numEdges p=${$(p)} sp=${$(sp)}")

    val types = if ($(withVertexType)) {
      datatype.select("vertex", "type").rdd
        .filter(row => !row.anyNull)
        .map(row => (row.getLong(0), row.getString(1)))
    } else {
      null.asInstanceOf[RDD[(Long, String)]]
    }

    val edges = if ($(withVertexType)) {
      iniEdges.groupByKey($(partitionNum))
        .map(e => (e._1, e._2))
        .leftOuterJoin(types)
        .map{
          case (id, (neighs, optTag)) =>
            (id, optTag.getOrElse("null"), neighs)
        }
        .map(e => (e._1, tags.contains(e._2), e._3))
    } else {
      iniEdges.groupByKey($(partitionNum))
        .map(e => (e._1, true, e._2))
    }
    edges.persist($(storageLevel))
    iniEdges.unpersist()

    val modelContext = new ModelContext($(psPartitionNum), minId, maxId + 1, -1, "hyperVertices", SparkContext.getOrCreate().hadoopConfiguration)
    val model = HyperANFPSModel.fromMinMax(modelContext, index, $(useBalancePartition), $(balancePartitionPercent))

    val seed = System.currentTimeMillis()
    val graph = edges.map(e => (e._1, if (e._2) 1 else 0, e._3))
      .mapPartitionsWithIndex((index, it) =>
      Iterator.single(HyperANFGraphPartition.apply(index, it, $(p), $(sp), seed)))

    graph.persist($(storageLevel))
    graph.foreachPartition(_ => Unit)
    edges.unpersist()

    var start = System.currentTimeMillis()
    graph.map(_.init(model)).collect()
    println(s"Initialize model on ps successfully, cost time: ${(System.currentTimeMillis()-start)/1000.0}s.")
    start = System.currentTimeMillis()
    model.checkpoint()
    println(s"finish checkpoint, cost time: ${(System.currentTimeMillis()-start)/1000.0}s.")

    println("begin vertex ANF computing.")
    start = System.currentTimeMillis()
    var r: Int = 1 // iteration round
    var numActives: Long = 1L
    var graphANF: Long = 0
    var graphANFOld: Long = 0
    if (!$(isSaveCounter)) {
      var newGraph = null.asInstanceOf[RDD[(Long, Iterator[(Long, Long, Long)])]]
      do {
        numActives = graph.map(_.process(model, $(msgNumBatch))).reduce(_ + _)
        newGraph = edgesOnly.mapPartitions{iter =>  Iterator(HyperResultGet.processANF(model, $(msgNumBatch), iter.toArray))}
        newGraph.persist($(storageLevel))

        val retRDD = newGraph.map(row => row._2).flatMap(row => row)
          .map {
            case (node, anf, cardDiff) => Row.fromSeq(Seq[Any](node, anf.toFloat, cardDiff.toFloat, s"order_$r"))
          }
        retRDD.persist($(storageLevel))
        retRDD.count()

        val outputSchema = schema(false)
        val dataFrame = dataset.sparkSession.createDataFrame(retRDD, outputSchema)
        if (r == 1) {
          GraphIO.save(dataFrame, output)
        } else {
          GraphIO.appendSave(dataFrame, output)
        }

        //newGraph.count()
        graphANFOld = graphANF
        graphANF = newGraph.map(row => row._1).reduce(_ + _)
        println(s"iter=$r, activeMsgs=$numActives, graphANFOld=$graphANFOld, graphANF=$graphANF")
        model.updateReadCounter()
        r += 1
      } while (r <= $(maxIter) && (graphANF - graphANFOld) > 0)
      edgesOnly.unpersist()
      newGraph.unpersist()
    } else {
      var newGraph = null.asInstanceOf[RDD[(Long, Iterator[(Long, Long, Long, HyperLogLogPlus)])]]
      do {
        numActives = graph.map(_.process(model, $(msgNumBatch))).reduce(_ + _)
        newGraph = edgesOnly.mapPartitions{iter =>  Iterator(HyperResultGet.processANFCounter(model, $(msgNumBatch), iter.toArray))}
        newGraph.persist($(storageLevel))

        val retRDD = newGraph.map(row => row._2).flatMap(row => row)
          .map {
            case (node, anf, cardDiff, hllCounter) => Row.fromSeq(Seq[Any](node, anf.toFloat, cardDiff.toFloat, hllCounter.getBytes, s"order_$r"))
          }
        retRDD.persist($(storageLevel))
        retRDD.count()

        val outputSchema = schema(true)
        val dataFrame = dataset.sparkSession.createDataFrame(retRDD, outputSchema)
        if (r == 1) {
          GraphIO.saveParquet(dataFrame, output)
        } else {
          GraphIO.appendSaveParquet(dataFrame, output)
        }

        //newGraph.count()
        graphANFOld = graphANF
        graphANF = newGraph.map(row => row._1).reduce(_ + _)
        println(s"iter=$r, activeMsgs=$numActives, graphANFOld=$graphANFOld, graphANF=$graphANF")
        model.updateReadCounter()
        r += 1
      } while (r <= $(maxIter) && (graphANF - graphANFOld) > 0)
      edgesOnly.unpersist()
      newGraph.unpersist()
    }

    //val numNodes = model.numNodes()
    //val maxCardinality = model.maxCardinality()
    //println(s"numNodes=$numNodes maxCardinality=$maxCardinality")
    println(s"finish vertex ANF computing, cost time: ${(System.currentTimeMillis()-start)/1000.0}s.")

    /*
    val retRDD = newGraph.map(row => row._2).flatMap(row => row)
      .map {
        case (node, anf, cardDiff) => Row.fromSeq(Seq[Any](node, anf.toFloat, cardDiff.toFloat))
      }
    retRDD.persist($(storageLevel))
    retRDD.count()
    edgesOnly.unpersist()
    newGraph.unpersist()

    val outputSchema = schema(false)
    dataset.sparkSession.createDataFrame(retRDD, outputSchema)
     */
  }

  def summarizeApplyOp(iterator: Iterator[(Long, Long)]): Iterator[(Long, Long, Long)] = {
    var minId = Long.MaxValue
    var maxId = Long.MinValue
    var numEdges = 0
    while (iterator.hasNext) {
      val entry = iterator.next()
      val (src, dst) = (entry._1, entry._2)
      if (src <= dst) {
        minId = math.min(minId, src)
        maxId = math.max(maxId, dst)
      } else {
        minId = math.min(minId, dst)
        maxId = math.max(maxId, src)
      }
      numEdges += 1
    }

    Iterator.single((minId, maxId, numEdges))
  }

  def summarizeReduceOp(t1: (Long, Long, Long),
                        t2: (Long, Long, Long)): (Long, Long, Long) =
    (math.min(t1._1, t2._1), math.max(t1._2, t2._2), t1._3 + t2._3)

  def splitPartitionIds(model: HyperANFPSModel): (Array[Int], Array[Int]) = {
    val parts = PSAgentContext.get().getMatrixMetaManager.getPartitions(model.matrixId)
    Collections.shuffle(parts)

    val length = parts.size()
    val sizes = new Array[Int]($(partitionNum))
    for (i <- sizes.indices)
      sizes(i) = length / sizes.length
    for (i <- 0 until (length % sizes.length))
      sizes(i) += 1

    for (i <- 1 until sizes.length)
      sizes(i) += sizes(i - 1)

    val partitionIds = new Array[Int](length)
    for (i <- 0 until length)
      partitionIds(i) = parts.get(i).getPartitionId

    (partitionIds, sizes)
  }

  override def transformSchema(schema: StructType): StructType = {
    StructType(Seq(
      StructField(s"${$(outputNodeIdCol)}", LongType, nullable = false),
      StructField(s"${$(vertexANFCol)}", FloatType, nullable = false)
    ))
  }

  def schema(verbose: Boolean): StructType = {
    if (verbose)
      StructType(Seq(
        StructField(s"${$(outputNodeIdCol)}", LongType, nullable = false),
        StructField(s"${$(vertexANFCol)}", FloatType, nullable = false),
        StructField(s"${$(cardDiffCol)}", FloatType, nullable = false),
        StructField(s"${$(counterCol)}", ArrayType(ByteType, false), nullable = false),
        StructField("order", StringType, nullable = false)
      ))
    else
      StructType(Seq(
        StructField(s"${$(outputNodeIdCol)}", LongType, nullable = false),
        StructField(s"${$(vertexANFCol)}", FloatType, nullable = false),
        StructField(s"${$(cardDiffCol)}", FloatType, nullable = false),
        StructField("order", StringType, nullable = false)
      ))
  }

  override def copy(extra: ParamMap): Transformer = defaultCopy(extra)

  override def transform(dataset: Dataset[_]): DataFrame = {
    null.asInstanceOf[DataFrame]
  }
}

object HyperVertices {
  def startPS(sc: SparkContext): Unit = {
    PSContext.getOrCreate(sc)
  }

  def stopPS(): Unit = {
    PSContext.stop()
  }
}