/*
 * Copyright (c) 2016-2018 "Neo4j Sweden, AB" [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.okapi.relational.api.table

import org.opencypher.okapi.api.table.CypherRecords
import org.opencypher.okapi.api.value.CypherValue.CypherMap
import org.opencypher.okapi.impl.exception.IllegalArgumentException
import org.opencypher.okapi.ir.api.block.{Asc, Desc, SortItem}
import org.opencypher.okapi.ir.api.expr.{AliasExpr, Expr, Var}
import org.opencypher.okapi.relational.impl.physical.{Ascending, Descending, JoinType, Order}
import org.opencypher.okapi.relational.impl.table.RecordHeader

trait RelationalCypherRecords[T <: FlatRelationalTable[T]] extends CypherRecords {

  type R <: RelationalCypherRecords[T]

  def from(header: RecordHeader, table: T, displayNames: Option[Seq[String]] = None): R

  def table: T

  override def physicalColumns: Seq[String] = table.physicalColumns

  def header: RecordHeader

  def select(expr: Expr, epxrs: Expr*): R = {
    val allExprs = expr +: epxrs
    val aliasExprs = allExprs.collect { case a: AliasExpr => a }

    val headerWithAliases = header.withAlias(aliasExprs: _*)

    val selectHeader = headerWithAliases.select(allExprs: _*)
    val logicalColumns = allExprs.flatMap(_.owner).collect{
      case v: Var => v.withoutType
    }.distinct

    from(selectHeader, table.select(allExprs.map(headerWithAliases.column).distinct: _*), Some(logicalColumns))
  }

  def filter(expr: Expr)(implicit parameters: CypherMap): R = {
    val filteredTable = table.filter(expr)(header, parameters)
    from(header, filteredTable)
  }

  def drop(exprs: Expr*): R = {
    val updatedHeader = header -- exprs.toSet
    if (updatedHeader.columns.size < header.columns.size) {
      val updatedTable = table.drop(exprs.map(header.column): _*)
      from(updatedHeader, updatedTable)
    } else {
      from(updatedHeader, table)
    }
  }

  def addColumn(expr: Expr)(implicit parameters: CypherMap): R = {
    if (header.contains(expr)) {
      val updatedHeader = expr match {
        case a: AliasExpr => header.withAlias(a)
        case _ => header
      }
      from(updatedHeader, table)
    } else {
      val updatedHeader = expr match {
        case a: AliasExpr => header.withExpr(a.expr).withAlias(a)
        case _ => header.withExpr(expr)
      }
      val updatedTable = table.withColumn(updatedHeader.column(expr), expr)(updatedHeader, parameters)
      from(updatedHeader, updatedTable)
    }
  }

  def copyColumn(fromColumn: Expr, toColumn: Expr)(implicit parameters: CypherMap): R = {
    val updatedHeader = header.withExpr(toColumn)
    val updatedData = table.withColumn(updatedHeader.column(toColumn), fromColumn)(header, parameters)
    from(updatedHeader, updatedData)
  }

  def renameColumns(renamings: (Expr, String)*)(headerOpt: Option[RecordHeader] = None): R = {
    val updatedHeader = headerOpt.getOrElse(renamings.foldLeft(header) {
      case (currentHeader, (expr, newColumn)) => currentHeader.withColumnRenamed(expr, newColumn)
    })

    val updatedTable = renamings.foldLeft(table) {
      case (currentTable, (expr, newColumn)) => currentTable.withColumnRenamed(header.column(expr), newColumn)
    }
    from(updatedHeader, updatedTable)
  }

  def withColumnRenamed(oldColumn: Expr, newColumn: String): R = {
    val updatedHeader = header.withColumnRenamed(oldColumn, newColumn)
    val updatedTable = table.withColumnRenamed(header.column(oldColumn), newColumn)
    from(updatedHeader, updatedTable)
  }

  def orderBy(sortItems: SortItem[Expr]*): R = {
    val tableSortItems: Seq[(String, Order)] = sortItems.map {
      case Asc(expr) => header.column(expr) -> Ascending
      case Desc(expr) => header.column(expr) -> Descending
    }
    from(header, table.orderBy(tableSortItems: _*))
  }

  def withAliases(originalToAlias: AliasExpr*): R = {
    val headerWithAliases = header.withAlias(originalToAlias: _*)
    from(headerWithAliases, table)
  }

  def removeVars(vars: Set[Var]): R = {
    val updatedHeader = header -- vars
    val keepColumns = updatedHeader.columns.toSeq.sorted
    val updatedData = table.select(keepColumns: _*)
    from(updatedHeader, updatedData)
  }

  def unionAll(other: R): R = {
    val leftColumns = table.physicalColumns
    val rightColumns = other.table.physicalColumns

    if (leftColumns.size != rightColumns.size) {
      throw IllegalArgumentException("same number of columns", s"left: $leftColumns right: $rightColumns")
    }
    if (leftColumns.toSet != rightColumns.toSet) {
      throw IllegalArgumentException("same column names", s"left: $leftColumns right: $rightColumns")
    }

    val orderedTable = if (leftColumns != rightColumns) {
      other.table.select(leftColumns: _*)
    } else {
      other.table
    }
    val unionData = table.unionAll(orderedTable)
    from(header, unionData)
  }

  def distinct: R = {
    from(header, table.distinct)
  }

  def distinct(fields: Var*): R = {
    from(header, table.distinct(fields.flatMap(header.expressionsFor).map(header.column).sorted: _*))
  }

  def join(other: R, joinType: JoinType, joinExprs: (Expr, Expr)*): R = {
    val joinHeader = header join other.header

    val cleanOther = if (table.physicalColumns.toSet ++ other.table.physicalColumns.toSet != joinHeader.columns) {
      val renameColumns = other.header.expressions
        .filter(expr => other.header.column(expr) != joinHeader.column(expr))
        .map { expr => expr -> joinHeader.column(expr) }.toSeq
      other.renameColumns(renameColumns: _*)().asInstanceOf[R]
    } else other

    val joinCols = joinExprs.map { case (l, r) => header.column(l) -> cleanOther.header.column(r) }
    val joinData = table.join(cleanOther.table, joinType, joinCols: _*)
    from(joinHeader, joinData)
  }
}
