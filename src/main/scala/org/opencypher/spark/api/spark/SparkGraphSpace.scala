package org.opencypher.spark.api.spark

import org.apache.spark.sql.SparkSession
import org.opencypher.spark.api.graph.GraphSpace
import org.opencypher.spark.api.ir.global.TokenRegistry
import org.opencypher.spark.impl.record.SparkCypherRecordsTokens
import org.opencypher.spark.impl.spark.SparkGraphLoading

trait SparkGraphSpace extends GraphSpace {

  override type Space = SparkGraphSpace
  override type Graph = SparkCypherGraph
  override type Records = SparkCypherRecords

  // TODO: Remove
  def tokens: SparkCypherRecordsTokens

  def session: SparkSession
}

object SparkGraphSpace extends SparkGraphLoading with Serializable {
  def empty(sparkSession: SparkSession, registry: TokenRegistry) = new SparkGraphSpace {
    override def session: SparkSession = sparkSession
    override def tokens: SparkCypherRecordsTokens = SparkCypherRecordsTokens(registry)
    override def base: SparkCypherGraph = SparkCypherGraph.empty(this)
  }
}