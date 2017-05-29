package org.opencypher.spark.impl.flat

import cats.Monoid
import org.opencypher.spark.api.expr._
import org.opencypher.spark.api.ir.pattern.{AllGiven, EveryNode, EveryRelationship}
import org.opencypher.spark.api.record._
import org.opencypher.spark.api.types._
import org.opencypher.spark.impl.logical.{GraphSource, NamedLogicalGraph}
import org.opencypher.spark.impl.syntax.header._
import org.opencypher.spark.impl.syntax.util.traversable._
import org.opencypher.spark.impl.util.{Found, Replaced}

class FlatOperatorProducer(implicit context: FlatPlannerContext) {

  private val globals = context.globalsRegistry
  private val schema = context.schema

  import globals._

  private implicit val typeVectorMonoid = new Monoid[Vector[CypherType]] {
    override def empty: Vector[CypherType] = Vector.empty
    override def combine(x: Vector[CypherType], y: Vector[CypherType]): Vector[CypherType] = x ++ y
  }

  // TODO: Unalias dependencies MATCH (n) WITH n.prop AS m, n WITH n // frees up m, don't lose n.prop
  def select(fields: Set[Var], in: FlatOperator): Select = {
    // TODO: Error handling

    // TODO: doesn't work! reports removing slots, but returns header with them still in!
    val (header, removed) = in.header.update(selectFields {
      case RecordSlot(_, content: FieldSlotContent) => fields(content.field)
      case _ => false
    })
    val removedSlots = removed.map(_.it)
    val nextHeader = header.slots.foldLeft(RecordHeader.empty) {
      case (acc, s) if removedSlots.contains(s) => acc
      case (acc, s) => acc.update(addContent(s.content))._1
    }

    Select(fields, in, nextHeader)
  }

  def filter(expr: Expr, in: FlatOperator): Filter = {
    in.header

//    expr match {
//      case HasLabel(n, label) =>
//        in.header.contents.map { c =>
//
//        }
//      case _ => in.header
//    }

    // TODO: Should replace SlotContent expressions with detailed type of entity
    // TODO: Should reduce width of header due to more label information

    Filter(expr, in, in.header)
  }

  def nodeScan(node: Var, _nodeDef: EveryNode, prev: FlatOperator): NodeScan = {
    val nodeDef = if (_nodeDef.labels.elts.isEmpty) EveryNode(AllGiven(schema.labels.map(globals.label))) else _nodeDef

    val givenLabels = nodeDef.labels.elts.map(ref => label(ref).name)

    val header = constructHeaderFromKnownLabels(node, givenLabels)

    NodeScan(node, nodeDef, prev, header)
  }

  private def constructHeaderFromKnownLabels(node: Var, labels: Set[String]) = {

    val impliedLabels = schema.impliedLabels.transitiveImplicationsFor(labels)
    val impliedKeys = impliedLabels.flatMap(label => schema.nodeKeyMap.keysFor(label).toSet)
    val possibleLabels = impliedLabels.flatMap(label => schema.optionalLabels.combinationsFor(label))
    val optionalKeys = possibleLabels.flatMap(label => schema.nodeKeyMap.keysFor(label).toSet)
    val optionalNullableKeys = optionalKeys.map { case (k, v) => k -> v.nullable }
    val allKeys = (impliedKeys ++ optionalNullableKeys).toSeq.map { case (k, v) => k -> Vector(v) }
    val keyGroups = allKeys.groups[String, Vector[CypherType]]

    val labelHeaderContents = (impliedLabels ++ possibleLabels).map {
      labelName => ProjectedExpr(HasLabel(node, label(labelName))(CTBoolean))
    }.toSeq

    val keyHeaderContents = keyGroups.toSeq.flatMap {
      case (k, types) => types.map { t => ProjectedExpr(Property(node, propertyKey(k))(t)) }
    }

    // TODO: Add is null column(?)

    // TODO: Check results for errors
    val (header, _) = RecordHeader.empty
      .update(addContents(OpaqueField(node) +: (labelHeaderContents ++ keyHeaderContents)))

    header
  }

  // TODO: Specialize per kind of slot content
  def project(it: ProjectedSlotContent, in: FlatOperator): FlatOperator = {
    val (newHeader, result) = in.header.update(addContent(it))

    result match {
      case _: Found[_] => in
      case _: Replaced[_] => Alias(it.expr, it.alias.get, in, newHeader)
      case _ => throw new NotImplementedError("No support yet for projecting non-attribute expressions") // TODO: Error handling
    }
  }

  // TODO: Specialize per kind of slot content
  def expandSource(source: Var, rel: Var, types: EveryRelationship, target: Var, in: FlatOperator): FlatOperator = {
    // TODO: This should consider multiple types per property
    val allNodeProperties = schema.nodeKeyMap.m.values.reduce(_ ++ _).toSeq.distinct
    val allLabels = schema.labels

    val targetLabelHeaderContents = allLabels.map {
      labelName => ProjectedExpr(HasLabel(target, label(labelName))(CTBoolean))
    }

    val targetKeyHeaderContents = allNodeProperties.map {
      case ((k, t)) => ProjectedExpr(Property(target, propertyKey(k))(t))
    }

    // TODO: This should consider multiple types per property
    val relKeyHeaderProperties = types.relTypes.elts.flatMap(t => schema.relationshipKeys(globals.relType(t).name).toSeq)
    val relKeyHeaderContents = relKeyHeaderProperties.map {
      case ((k, t)) => ProjectedExpr(Property(rel, propertyKey(k))(t))
    }

    val typeIdContent = ProjectedExpr(TypeId(rel)(CTInteger))

    val targetNode = OpaqueField(target)

    val (newHeader, _) = in.header.update(addContents(
      Seq(OpaqueField(rel), typeIdContent) ++ relKeyHeaderContents ++ Seq(targetNode) ++ targetLabelHeaderContents ++ targetKeyHeaderContents
    ))

    ExpandSource(source, rel, types, target, in, newHeader)
  }

  def planLoadGraph(logicalGraph: NamedLogicalGraph, source: GraphSource): LoadGraph = {

    LoadGraph(logicalGraph, source)
  }
}
