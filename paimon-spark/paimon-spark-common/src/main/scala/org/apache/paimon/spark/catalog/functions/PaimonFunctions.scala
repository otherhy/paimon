/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.spark.catalog.functions

import org.apache.paimon.data.serializer.InternalRowSerializer
import org.apache.paimon.shade.guava30.com.google.common.collect.{ImmutableList, ImmutableMap}
import org.apache.paimon.spark.SparkInternalRowWrapper
import org.apache.paimon.spark.SparkTypeUtils.toPaimonRowType
import org.apache.paimon.spark.catalog.functions.PaimonFunctions._
import org.apache.paimon.table.sink.KeyAndBucketExtractor.{bucket, bucketKeyHashCode}
import org.apache.paimon.utils.ProjectedRow

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.catalog.functions.{BoundFunction, ScalarFunction, UnboundFunction}
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.types.DataTypes.{IntegerType, StringType}

import javax.annotation.Nullable

object PaimonFunctions {

  val BUCKET: String = "bucket"
  val MAX_PT: String = "max_pt"

  private val FUNCTIONS = ImmutableMap.of(
    BUCKET,
    new BucketFunction,
    MAX_PT,
    new MaxPtFunction
  )

  val names: ImmutableList[String] = FUNCTIONS.keySet.asList()

  @Nullable
  def load(name: String): UnboundFunction = FUNCTIONS.get(name)
}

/**
 * A function returns the bucket ID based on the number of buckets and bucket keys.
 *
 * params arg0: bucket number, arg1...argn bucket keys.
 */
class BucketFunction extends UnboundFunction {

  override def bind(inputType: StructType): BoundFunction = {
    assert(inputType.fields(0).dataType == IntegerType, "bucket number field must be integer type")

    val bucketKeyStructType = StructType(inputType.tail)
    val bucketKeyRowType = toPaimonRowType(bucketKeyStructType)
    val serializer = new InternalRowSerializer(bucketKeyRowType)
    val mapping = (1 to bucketKeyRowType.getFieldCount).toArray
    val reusedRow =
      new SparkInternalRowWrapper(-1, inputType, inputType.fields.length)

    new ScalarFunction[Int]() {
      override def inputTypes: Array[DataType] = inputType.fields.map(_.dataType)

      override def resultType: DataType = IntegerType

      override def name: String = BUCKET

      override def canonicalName: String = {
        // We have to override this method to make it support canonical equivalent
        s"paimon.bucket(int, ${bucketKeyStructType.fields.map(_.dataType.catalogString).mkString(", ")})"
      }

      override def produceResult(input: InternalRow): Int = {
        val numberBuckets = input.getInt(0)
        bucket(
          bucketKeyHashCode(
            serializer.toBinaryRow(
              ProjectedRow.from(mapping).replaceRow(reusedRow.replace(input)))),
          numberBuckets)
      }

      override def isResultNullable: Boolean = false
    }
  }

  override def description: String = name

  override def name: String = BUCKET
}

/**
 * For partitioned tables, this function returns the maximum value of the first level partition of
 * the partitioned table, sorted alphabetically. Note, empty partitions will be skipped. For
 * example, a partition created by `alter table ... add partition ...`.
 */
class MaxPtFunction extends UnboundFunction {

  override def bind(inputType: StructType): BoundFunction = {
    if (inputType.fields.length != 1)
      throw new UnsupportedOperationException(
        "Wrong number of inputs, expected 1 but got " + inputType.fields.length)
    val identifier = inputType.fields(0)
    assert(identifier.dataType eq StringType, "table name must be string type")

    new ScalarFunction[String]() {
      override def inputTypes: Array[DataType] = Array[DataType](identifier.dataType)

      override def resultType: DataType = StringType

      override def produceResult(input: InternalRow): String = {
        // Does not need to implement the `produceResult` method,
        // since `ReplacePaimonFunctions` will replace it with partition literal.
        throw new IllegalStateException("This method should not be called")
      }

      override def name: String = MAX_PT

      override def canonicalName: String =
        "paimon.max_pt(" + identifier.dataType.catalogString + ")"

      override def isResultNullable: Boolean = false
    }
  }

  override def description: String = name

  override def name: String = MAX_PT
}
