package org.opencypher.spark.impl.instances.spark

import org.apache.spark.sql.{Column, Row}
import org.opencypher.spark.api.expr._
import org.opencypher.spark.api.record._
import org.opencypher.spark.api.spark.SparkCypherRecords
import org.opencypher.spark.api.value.CypherValueUtils._
import org.opencypher.spark.impl.classes.Transform
import org.opencypher.spark.impl.exception.Raise
import org.opencypher.spark.impl.instances.spark.RowUtils._
import org.opencypher.spark.impl.instances.spark.SparkSQLExprMapper.asSparkSQLExpr
import org.opencypher.spark.impl.physical.RuntimeContext

trait SparkCypherRecordsInstances extends Serializable {

  /*
   * Used when the predicate depends on values not stored inside the dataframe.
   */
  case class cypherFilter(header: RecordHeader, expr: Expr)
                         (implicit context: RuntimeContext) extends (Row => Option[Boolean]) {
    def apply(row: Row): Option[Boolean] = expr match {
      case Equals(p: Property, c: Const) =>
        Some(row.getCypherValue(p, header) == row.getCypherValue(c, header))

      case LessThan(lhs, rhs) =>
        row.getCypherValue(lhs, header) < row.getCypherValue(rhs, header)

      case LessThanOrEqual(lhs, rhs) =>
        row.getCypherValue(lhs, header) <= row.getCypherValue(rhs, header)

      case GreaterThan(lhs, rhs) =>
        row.getCypherValue(lhs, header) > row.getCypherValue(rhs, header)

      case GreaterThanOrEqual(lhs, rhs) =>
        row.getCypherValue(lhs, header) >= row.getCypherValue(rhs, header)

      case x =>
        Raise.notYetImplemented(s"Predicate $x")
    }
  }

  implicit def sparkCypherRecordsTransform(implicit context: RuntimeContext) =
    new Transform[SparkCypherRecords] with Serializable {

      private def liftTernary(f: Row => Option[Boolean]): (Row => Boolean) = {
        (r: Row) =>
          f(r) match {
            case None => false
            case Some(x) => x
          }
      }

      override def filter(subject: SparkCypherRecords, expr: Expr, newHeader: RecordHeader): SparkCypherRecords = {

        val filteredRows = asSparkSQLExpr(subject.header, expr, subject.data) match {
          case Some(sqlExpr) =>
            subject.data.where(sqlExpr)
          case None =>
            val predicate = cypherFilter(newHeader, expr)
            subject.data.filter(liftTernary(predicate))
        }

        val selectedColumns = newHeader.slots.map { c =>
          val name = context.columnName(c)
          filteredRows.col(name)
        }

        val newData = filteredRows.select(selectedColumns: _*)

        SparkCypherRecords.create(newHeader, newData)(subject.space)
      }

      override def select(subject: SparkCypherRecords, fields: IndexedSeq[Var], newHeader: RecordHeader)
      : SparkCypherRecords = {
        val data = subject.data
        val columns = fields.map { f =>
          data.col(context.columnName(subject.header.slotsFor(f).head))
        }
        val newData = subject.data.select(columns: _*)

        SparkCypherRecords.create(newHeader, newData)(subject.space)
      }

      override def reorder(subject: SparkCypherRecords, newHeader: RecordHeader): SparkCypherRecords = {
        val columns = newHeader.slots.map(context.columnName)

        val newData = subject.data.select(columns.head, columns.tail: _*)

        SparkCypherRecords.create(newHeader, newData)(subject.space)
      }

      override def alias2(subject: SparkCypherRecords, expr: Expr, v: Var, newHeader: RecordHeader)
      : SparkCypherRecords = {
        val oldSlot = subject.header.slotsFor(expr).head

        val newSlot = newHeader.slotsFor(v).head

        val oldColumnName = context.columnName(oldSlot)
        val newColumnName = context.columnName(newSlot)

        val newData = if (subject.data.columns.contains(oldColumnName)) {
          subject.data.withColumnRenamed(oldColumnName, newColumnName)
        } else {
          Raise.columnNotFound(oldColumnName)
        }

        SparkCypherRecords.create(newHeader, newData)(subject.space)
      }

      override def project(subject: SparkCypherRecords, expr: Expr, newHeader: RecordHeader): SparkCypherRecords = {

        val newData = asSparkSQLExpr(newHeader, expr, subject.data) match {
          case None => Raise.notYetImplemented(s"projecting $expr")

          case Some(sparkSqlExpr) =>
            // align the name of the column to what the header expects
            val name = newHeader.slotsFor(expr).headOption match {
              case None => Raise.multipleSlotsForExpression()
              case Some(s) => context.columnName(s)
            }
            val columnsToSelect = subject.data.columns.map(subject.data.col) :+ sparkSqlExpr.as(name)
            subject.data.select(columnsToSelect: _*)
        }

        SparkCypherRecords.create(newHeader, newData)(subject.space)
      }

      override def join(lhs: SparkCypherRecords, rhs: SparkCypherRecords)
                       (lhsSlot: RecordSlot, rhsSlot: RecordSlot): SparkCypherRecords =
        join(lhs, rhs, lhs.header ++ rhs.header)(lhsSlot, rhsSlot)

      override def join(lhs: SparkCypherRecords, rhs: SparkCypherRecords, jointHeader: RecordHeader)
                       (lhsSlot: RecordSlot, rhsSlot: RecordSlot): SparkCypherRecords = {

        if (lhs.space == rhs.space) {
          val lhsData = lhs.data
          val rhsData = rhs.data

          val lhsColumn = lhsData.col(context.columnName(lhsSlot))
          val rhsColumn = rhsData.col(context.columnName(rhsSlot))

          val joinExpr: Column = lhsColumn === rhsColumn
          val jointData = lhsData.join(rhsData, joinExpr, "inner")

          SparkCypherRecords.create(jointHeader, jointData)(lhs.space)
        } else {
          Raise.graphSpaceMismatch()
        }
      }
    }
}