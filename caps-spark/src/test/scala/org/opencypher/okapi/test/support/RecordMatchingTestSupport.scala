/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
 */
package org.opencypher.okapi.test.support

import org.opencypher.okapi.api.table.CypherRecords
import org.opencypher.okapi.api.value.CypherValue._
import org.opencypher.okapi.impl.table.CAPSRecordHeader._
import org.opencypher.okapi.impl.table._
import org.opencypher.okapi.impl.spark.CAPSConverters._
import org.opencypher.okapi.impl.spark.CAPSRecords
import org.opencypher.okapi.ir.api.expr.Var
import org.opencypher.okapi.relational.impl.table.{FieldSlotContent, OpaqueField, ProjectedExpr, RecordHeader}
import org.opencypher.okapi.test.CAPSTestSuite
import org.scalatest.Assertion

import scala.collection.JavaConverters._
import scala.collection.immutable.{Bag, HashedBagConfiguration}

trait RecordMatchingTestSupport {

  self: CAPSTestSuite =>

  implicit val bagConfig: HashedBagConfiguration[CypherMap] = Bag.configuration.compact[CypherMap]

  implicit class RecordMatcher(records: CAPSRecords) {
    def shouldMatch(expected: CypherMap*): Assertion = {
      records.collect.toBag should equal(Bag(expected: _*))
    }

    def shouldMatch(expectedRecords: CAPSRecords): Assertion = {
      records.header should equal(expectedRecords.header)

      val actualData = records.toLocalIterator.asScala.toSet
      val expectedData = expectedRecords.toLocalIterator.asScala.toSet
      actualData should equal(expectedData)
    }

    def shouldMatchOpaquely(expectedRecords: CAPSRecords): Assertion = {
      RecordMatcher(projected(records)) shouldMatch projected(expectedRecords)
    }

    private def projected(records: CAPSRecords): CAPSRecords = {
      val newSlots = records.header.slots.map(_.content).map {
        case slot: FieldSlotContent => OpaqueField(slot.field)
        case slot: ProjectedExpr    => OpaqueField(Var(slot.expr.withoutType)(slot.cypherType))
      }
      val newHeader = RecordHeader.from(newSlots: _*)
      val newData = records.data.toDF(newHeader.internalHeader.columns: _*)
      CAPSRecords.verifyAndCreate(newHeader, newData)(records.caps)
    }
  }

  implicit class RichRecords(records: CypherRecords) {
    val capsRecords = records.asCaps
    import org.opencypher.okapi.impl.spark.DataFrameOps._

    // TODO: Remove this and replace usages with toMapsWithCollectedEntities below
    // probably use this name though, and have not collecting be the special case
    def toMaps: Bag[CypherMap] = {
      val rows = capsRecords.toDF().collect().map { r =>
        val properties = capsRecords.header.slots.map { s =>
          s.content match {
            case f: FieldSlotContent => f.field.name -> r.getCypherValue(f.key, capsRecords.header)
            case x                   => x.key.withoutType -> r.getCypherValue(x.key, capsRecords.header)
          }
        }.toMap
        CypherMap(properties)
      }
      Bag(rows: _*)
    }

    def toMapsWithCollectedEntities: Bag[CypherMap] =
      Bag(capsRecords.toCypherMaps.collect(): _*)
  }
}
