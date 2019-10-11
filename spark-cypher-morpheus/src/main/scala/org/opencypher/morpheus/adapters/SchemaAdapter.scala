package org.opencypher.morpheus.adapters

import org.apache.spark.graph.api.PropertyGraphType
import org.opencypher.okapi.api.schema.PropertyGraphSchema

case class SchemaAdapter(schema: PropertyGraphSchema) extends PropertyGraphType {

  override def labelSets: Set[Set[String]] = schema.labelCombinations.combos

  override def relationshipTypes: Set[String] = schema.relationshipTypes


}
