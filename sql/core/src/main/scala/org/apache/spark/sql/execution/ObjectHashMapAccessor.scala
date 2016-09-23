/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution

import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.hash.Murmur3_x86_32

/**
 * Provides helper methods for generated code to use ObjectHashSet with a
 * generated class (having key and value columns as corresponding java type
 * fields). This implementation saves the entire overhead of UnsafeRow
 * conversion for both key type (like in BytesToBytesMap) and value type
 * (like in BytesToBytesMap and VectorizedHashMapGenerator).
 * <p>
 * It has been carefully optimized to minimize memory reads/writes, with
 * minimalistic code to fit better in CPU instruction cache. Unlike the other
 * two maps used by HashAggregateExec, this has no limitations on the key or
 * value column types.
 * <p>
 * The basic idea being that all of the key and value columns will be
 * individual fields in a generated java class having corresponding java
 * types. Storage of a column value in the map is a simple matter of assignment
 * of incoming variable to the corresponding field of the class object and
 * access is likewise read from that field of class . Nullability information
 * is crammed in long bit-mask fields which are generated as many required
 * (instead of unnecessary overhead of something like a BitSet).
 * <p>
 * Hashcode and equals methods are generated for the key column fields.
 * Having both key and value fields in the same class object helps both in
 * cutting down of generated code as well as cache locality and reduces at
 * least one memory access for each row. In testing this alone has shown to
 * improve performance by ~25% in simple group by queries. Furthermore, this
 * class also provides for inline hashcode and equals methods so that incoming
 * register variables in generated code can be directly used (instead of
 * stuffing into a lookup key that will again read those fields inside). The
 * class hashcode method is supposed to be used only internally by rehashing
 * and that too is just a field cached in the class object that is filled in
 * during the initial insert (from the inline hashcode).
 * <p>
 * For memory management this uses a simple approach of starting with an
 * estimated size, then improving that estimate for future in a rehash where
 * the rehash will also collect the actual size of current entries.
 * If the rehash tells that no memory is available, then it will fallback
 * to dumping the current map into MemoryManager and creating a new one
 * with merge being done by an external sorter in a manner similar to
 * how UnsafeFixedWidthAggregationMap handles the situation. Caller can
 * instead decide to dump the entire map in that scenario like when using
 * for a HashJoin.
 * <p>
 * Overall this map is 5-10X faster than UnsafeFixedWidthAggregationMap
 * and 2-3X faster than VectorizedHashMapGenerator. It is generic enough
 * to be used for both group by aggregation as well as for HashJoins.
 */
final class ObjectHashMapAccessor(ctx: CodegenContext, classPrefix: String,
    keySchema: StructType, valueSchema: StructType) {

  val schema = keySchema ++ valueSchema
  val valueIndex = keySchema.length

  private[this] val murmurClass = classOf[Murmur3_x86_32].getName
  private[this] val nullsMaskPrefix = "nullsMask"
  /**
   * Indicator value for "nullIndex" of a non-primitive nullable that can be
   * checked using its value rather than a separate bit mask.
   */
  private[this] val NULL_NON_PRIM = -2

  private[this] val (className, classVars, numNullVars) = {
    // check for existing class with same schema
    val types = schema.map(f => f.dataType -> f.nullable)
    val (newClassName, exists) = ctx.getClass(types) match {
      case Some(r) => (r, true)
      case None => (ctx.freshName(classPrefix), false)
    }

    // collect the null field declarations and equals code
    val nullMaskDeclarations = new StringBuilder
    val equalsCode = new StringBuilder

    // local variable name for other object in equals
    val other = "other"

    // current null bit mask variables (longs that will hold for 64 fields each)
    var numNulls = -1
    var currNullVar = ""

    val newClassVars = schema.indices.map { index =>
      val f = schema(index)
      val varName = s"field$index"
      val dataType = f.dataType
      val javaType = ctx.javaType(dataType)
      val (nullVar, nullIndex, isPrimitive) = if (f.nullable) {
        if (isPrimitiveType(dataType)) {
          numNulls += 1
          // each long can hold bit mask for 64 nulls
          val nullIndex = numNulls % 64
          if (nullIndex == 0) {
            currNullVar = s"$nullsMaskPrefix${numNulls / 64}"
            if (!exists) {
              nullMaskDeclarations.append(s"long $currNullVar;\n")
            }
          }
          (currNullVar, nullIndex, true)
        } else ("", NULL_NON_PRIM, false)
      } else ("", -1, isPrimitiveType(dataType))

      // generate equals code for key columns only
      if (!exists && index < valueIndex) {
        equalsCode.append(genEqualsCode(varName, nullVar, other, varName,
          nullVar, nullIndex, isPrimitive)).append("\n&& ")
      }
      (dataType, javaType, ExprCode("", nullVar, varName), nullIndex)
    }
    if (!exists) {
      // trim trailing "\n&& "
      equalsCode.setLength(equalsCode.length - 4)
      val classCode =
        s"""
          public static final class $newClassName {
            ${nullMaskDeclarations.toString()}
            ${newClassVars.map(e => s"${e._2} ${e._3.value};").mkString("\n")}

            final int hash;

            public $newClassName(int h) {
              this.hash = h;
            }

            public int hashCode() {
              return this.hash;
            }

            public boolean equals(Object o) {
              final $newClassName $other = ($newClassName)o;
              return $equalsCode;
            }
          };
        """
      // using addNewFunction to register the class since there is nothing
      // function specific in the addNewFunction method
      ctx.addNewFunction(newClassName, classCode)
      ctx.addClass(types, newClassName)
    }

    (newClassName, newClassVars, numNulls + 1)
  }

  /** get the generated class name */
  def getClassName: String = className

  /**
   * Generate code to calculate the hash code for given column variables that
   * correspond to the key columns in this class.
   */
  def generateHashCode(keyVars: Seq[ExprCode], hashVar: String,
      k1: String = ctx.freshName("k1")): String = classVars.zip(keyVars).map {
    case ((BooleanType, _, _, _), colVar) =>
      addHashInt(s"${colVar.value} ? 1 : 0", k1, hashVar, colVar.isNull)
    case ((ByteType | ShortType | IntegerType | DateType, _, _, _), colVar) =>
      addHashInt(colVar.value, k1, hashVar, colVar.isNull)
    case ((LongType | TimestampType, _, _, _), colVar) =>
      addHashLong(colVar.value, k1, hashVar, colVar.isNull)
    case ((FloatType, _, _, _), colVar) =>
      addHashInt(s"Float.floatToIntBits(${colVar.value})", k1, hashVar,
        colVar.isNull)
    case ((DoubleType, _, _, _), colVar) =>
      addHashLong(s"Double.doubleToLongBits(${colVar.value})", k1, hashVar,
        colVar.isNull)
    case ((d: DecimalType, _, _, _), colVar) =>
      addHashInt(s"${colVar.value}.fastHashCode()", k1, hashVar, colVar.isNull)
    case (_, colVar) =>
      addHashInt(s"${colVar.value}.hashCode()", k1, hashVar, colVar.isNull)
  }.mkString(s"int $hashVar = 42; int $k1;\n", "",
    s"$hashVar = $murmurClass.fmix($hashVar, ${keyVars.length});")

  /**
   * Generate code to compare equality of a given object (objVar) against
   * key column variables.
   */
  def generateEquals(keyVars: Seq[ExprCode],
      objVar: String): String = classVars.zip(keyVars).map {
    case ((dataType, _, ExprCode(_, nullVar, varName), nullIndex), colVar) =>
      genEqualsCode(colVar.value, colVar.isNull, objVar, varName,
        nullVar, nullIndex, isPrimitiveType(dataType))
  }.mkString("\n&& ")

  /**
   * Get the ExprCode for the key and/or value columns given a class object
   * variable. This also returns an initialization code that should be inserted
   * in generated code first.
   */
  def getColumnVars(objVar: String, onlyKeyVars: Boolean,
      onlyValueVars: Boolean): (String, Seq[ExprCode]) = {
    // Generate initial declarations for null masks to avoid reading those
    // repeatedly. Caller is supposed to insert the code at the start.
    val declarations = new StringBuilder
    val nullLocalVars = (0 until numNullVars).map { index =>
      val nullVar = s"$nullsMaskPrefix$index"
      val nullLocalVar = ctx.freshName("localNullsMask")
      declarations.append(s"final long $nullLocalVar = $objVar.$nullVar;\n")
      nullVar -> nullLocalVar
    }.toMap

    val vars = if (onlyKeyVars) classVars.take(valueIndex)
    else if (onlyValueVars) classVars.drop(valueIndex) else classVars

    val columnVars = vars.map { case (dataType, javaType, ev, nullIndex) =>
      val localVar = ctx.freshName("localField")
      ExprCode(s"final $javaType $localVar = $objVar.${ev.value};",
        nullLocalVars.get(ev.isNull).map(genNullCode(_, nullIndex))
            .getOrElse(if (nullIndex == NULL_NON_PRIM) s"($localVar == null)"
            else "false"), localVar)
    }
    (declarations.toString(), columnVars)
  }

  /**
   * Generate code to lookup the map or insert a new key, value if not found.
   */
  def generateMapGetOrInsert(mapVar: String, hashVar: String, objVar: String,
      keyVars: Seq[ExprCode], valueInitVars: Seq[ExprCode],
      valueInitCode: String): String = {
    val valueInit = generateUpdate(objVar, Seq.empty, valueInitVars,
      forKey = false, doCopy = false)
    s"""
      // lookup or insert the grouping key in map
      // using inline get call so that equals() is inline using
      // existing register variables instead of having to fill up
      // a lookup key fields and compare against those (thus saving
      //   on memory writes/reads vs just register reads)
      $className $objVar;
      {
        final int mask = $mapVar.mask();
        final Object[] data = $mapVar.getData();
        int pos = $hashVar & mask;
        int delta = 1;
        while (true) {
          final Object key = data[pos];
          if (key != null) {
            $objVar = ($className)key;
            if (${generateEquals(keyVars, objVar)}) {
              break;
            } else {
              // quadratic probing with values increase by 1, 2, 3, ...
              pos = (pos + delta) & mask;
              delta++;
            }
          } else {
            $objVar = new $className($hashVar);
            // initialize the value fields to defaults
            $valueInitCode
            $valueInit
            // initialize the key fields
            ${generateUpdate(objVar, Seq.empty, keyVars, forKey = true)}
            // insert into the map and rehash if required
            data[pos] = $objVar;
            $mapVar.handleNewInsert();

            break;
          }
        }
      }
    """
  }

  /**
   * Generate code to update a class object fields with given resultVars. If
   * accessors for fields have been generated (using <code>getColumnVars</code>)
   * then those can be passed for faster reads where required.
   *
   * @param objVar     the variable holding reference to the class object
   * @param columnVars accessors for object fields, if available
   * @param resultVars result values to be assigned to object fields
   * @param forKey     if true then update key fields else value fields
   * @param doCopy     if true then a copy of reference values is assigned
   *                   else only reference copy done
   * @param forInit    if true then this is for initialization of fields
   *                   after object creation so some checks can be skipped
   * @return code to assign objVar fields to given resultVars
   */
  def generateUpdate(objVar: String, columnVars: Seq[ExprCode],
      resultVars: Seq[ExprCode], forKey: Boolean,
      doCopy: Boolean = true, forInit: Boolean = true): String = {
    val fieldVars = if (forKey) classVars.take(valueIndex)
    else classVars.drop(valueIndex)

    val nullLocalVars = if (columnVars.isEmpty) {
      // get nullability from object fields
      fieldVars.map(e => genNullCode(s"$objVar.${e._3.isNull}", e._4))
    } else {
      // get nullability from already set local vars passed in columnVars
      columnVars.map(_.isNull)
    }

    fieldVars.zip(nullLocalVars).zip(resultVars).map { case (((dataType, _,
    fieldVar, nullIdx), nullLocalVar), resultVar) =>
      if (nullIdx == -1) {
        // if incoming variable is null, then default will get assigned
        // because the variable will be initialized with the default
        genVarAssignCode(objVar, resultVar, fieldVar.value, dataType, doCopy)
      } else if (nullIdx == NULL_NON_PRIM) {
        val varName = fieldVar.value
        s"""
          if (${resultVar.isNull}) {
            $objVar.$varName = null;
          } else {
            ${genVarAssignCode(objVar, resultVar, varName, dataType, doCopy)}
          }
        """
      } else {
        val nullVar = fieldVar.isNull
        // when initializing the object, no need to clear null mask
        val nullClear = if (forInit) ""
        else {
          s"""
            if ($nullLocalVar) {
              $objVar.$nullVar &= ~${genNullBitMask(nullIdx)};
            }
          """
        }
        s"""
          if (${resultVar.isNull}) {
            $objVar.$nullVar |= ${genNullBitMask(nullIdx)};
          } else {
            $nullClear
            ${genVarAssignCode(objVar, resultVar, fieldVar.value,
                dataType, doCopy)}
          }
        """
      }
    }.mkString("\n")
  }

  private def isPrimitiveType(dataType: DataType): Boolean = dataType match {
    case BooleanType | ByteType | ShortType | IntegerType |
         LongType | FloatType | DoubleType | TimestampType | DateType => true
    case _ => false
  }

  private def genVarAssignCode(objVar: String, resultVar: ExprCode,
      varName: String, dataType: DataType,
      doCopy: Boolean): String = dataType match {
    // if doCopy is true, then create a copy of some non-primitives that are
    // just hold reference to UnsafeRow bytes (and can change under the hood)
    case StringType if doCopy =>
      s"$objVar.$varName = UTF8String.fromBytes(${resultVar.value}.getBytes());"
    case (_: ArrayType | _: MapType | _: StructType) if doCopy =>
      s"$objVar.$varName = ${resultVar.value}.copy();"
    case _ =>
      s"$objVar.$varName = ${resultVar.value};"
  }

  private def genNullBitMask(nullIdx: Int): String =
    if (nullIdx > 0) s"(1L << $nullIdx)" else "1L"

  private def genNullCode(colVar: String, nullIndex: Int): String = {
    if (nullIndex > 0) {
      s"(($colVar & (1L << $nullIndex)) != 0L)"
    } else if (nullIndex == 0) {
      s"(($colVar & 1L) == 1L)"
    } else if (nullIndex == NULL_NON_PRIM) {
      s"($colVar == null)"
    } else "false"
  }

  private def genNotNullCode(colVar: String, nullIndex: Int): String = {
    if (nullIndex > 0) {
      s"(($colVar & (1L << $nullIndex)) == 0L)"
    } else if (nullIndex == 0) {
      s"(($colVar & 1L) == 0L)"
    } else if (nullIndex == NULL_NON_PRIM) {
      s"($colVar != null)"
    } else "true"
  }

  private def addHashInt(hashExpr: String, k1Var: String, h1Var: String,
      nullCode: String): String = {
    if (nullCode.isEmpty || nullCode == "false") {
      s"""
        $k1Var = $murmurClass.mixK1($hashExpr);
        $h1Var = $murmurClass.mixH1($h1Var, $k1Var);
      """
    } else {
      s"""
        $k1Var = $murmurClass.mixK1(($nullCode) ? -1
          : ($hashExpr));
        $h1Var = $murmurClass.mixH1($h1Var, $k1Var);
      """
    }
  }

  private def addHashLong(hashExpr: String,
      k1Var: String, h1Var: String, nullCode: String): String = {
    val longVar = ctx.freshName("longVar")
    if (nullCode.isEmpty || nullCode == "false") {
      s"""
        final long $longVar = $hashExpr;
        $k1Var = $murmurClass.mixK1((int)($longVar ^ ($longVar >>> 32)));
        $h1Var = $murmurClass.mixH1($h1Var, $k1Var);
      """
    } else {
      s"""
        final long $longVar;
        $k1Var = $murmurClass.mixK1(($nullCode) ? -1
          : (int)(($longVar = ($hashExpr)) ^ ($longVar >>> 32)));
        $h1Var = $murmurClass.mixH1($h1Var, $k1Var);
      """
    }
  }

  private def genEqualsCode(thisColVar: String, thisNullVar: String,
      otherVar: String, otherColVar: String, otherNullVar: String,
      nullIndex: Int, isPrimitive: Boolean): String = {
    if (nullIndex == -1) {
      if (isPrimitive) s"($thisColVar == $otherVar.$otherColVar)"
      else s"$thisColVar.equals($otherVar.$otherColVar)"
    } else if (nullIndex == NULL_NON_PRIM) {
      s"""($thisColVar != null ? $thisColVar.equals($otherVar.$otherColVar)
           : ($otherVar.$otherColVar) == null)
      """
    } else {
      val notNullCode = genNotNullCode(thisNullVar, nullIndex)
      val otherNotNullCode = genNotNullCode(s"$otherVar.$otherNullVar",
        nullIndex)
      s"""($notNullCode ? ($otherNotNullCode ? ($thisColVar ==
           $otherVar.$otherColVar) : false) : !$otherNotNullCode)
      """
    }
  }
}