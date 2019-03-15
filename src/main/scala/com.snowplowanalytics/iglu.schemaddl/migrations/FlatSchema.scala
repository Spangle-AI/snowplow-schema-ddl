/*
 * Copyright (c) 2016-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.iglu.schemaddl.migrations

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

import cats.data.State
import cats.instances.list._
import cats.instances.option._
import cats.syntax.alternative._

import io.circe.Json

import org.json4s.jackson.JsonMethods.compact

import com.snowplowanalytics.iglu.schemaddl.{ SubSchemas, StringUtils, Properties }
import com.snowplowanalytics.iglu.schemaddl.jsonschema.Pointer.SchemaPointer
import com.snowplowanalytics.iglu.schemaddl.jsonschema.json4s.implicits._
import com.snowplowanalytics.iglu.schemaddl.jsonschema.properties.CommonProperties
import com.snowplowanalytics.iglu.schemaddl.jsonschema.{Pointer, Schema}
import com.snowplowanalytics.iglu.schemaddl.jsonschema.properties.CommonProperties.Type

/**
  *
  * @param subschemas (order should not matter at this point)
  * @param required keys listed in `required` property, whose parents also listed in `required`
  *                 some of parent properties still can be `null` and thus not required
  * @param parents keys that are not primitive, but can contain important information (e.g. nullability)
  */
final case class FlatSchema(subschemas: SubSchemas, required: Set[SchemaPointer], parents: SubSchemas) {
  // TODO: remove parents

  def withLeaf(pointer: SchemaPointer, schema: Schema): FlatSchema = {
    val updatedSchema =
      if ((pointer.value.nonEmpty && !required.contains(pointer)) || schema.canBeNull)
        schema.copy(
          `type` = schema.`type` match {
            case None => Some(Type.Null)
            case Some(t) => Some(t.withNull)
          }
        )
      else schema
    FlatSchema(subschemas + (pointer -> updatedSchema), required, parents)
  }

  def withRequired(pointer: SchemaPointer, schema: Schema): FlatSchema = {
    val currentRequired = FlatSchema.getRequired(pointer, schema).filter(nestedRequired)
    this.copy(required = currentRequired ++ required)
  }

  def withParent(pointer: SchemaPointer, schema: Schema): FlatSchema =
    FlatSchema(subschemas, required, parents + (pointer -> schema))

  /** All parents are required */
  @tailrec def nestedRequired(current: SchemaPointer): Boolean =
    current.parent.flatMap(_.parent) match {
      case None | Some(Pointer.Root) => true  // Technically None should not be reached
      case Some(parent) => required.contains(parent) && nestedRequired(parent)
    }

  /** Any parent properties contain `null` in `type` or `enum` */
  def nestedNullable(pointer: SchemaPointer): Boolean =
    parents
      .filter { case (p, _) => p.isParentOf(pointer) }
      .foldLeft(false) { case (acc, (_, schema)) =>
        schema.`type`.exists(_.nullable) || schema.enum.exists(_.value.contains(Json.Null)) || acc
      }

  def checkUnionSubSchema(pointer: SchemaPointer): Boolean =
    subschemas
      .filter { case (p, _) => p.isParentOf(pointer) }
      .foldLeft(false) { case (acc, (_, schema)) =>
        schema.`type`.exists(_.isUnion) || acc
      }

  def toMap: Map[SchemaPointer, Schema] = ListMap(subschemas.toList: _*)

  def show: String = subschemas
    .map { case (pointer, schema) => s"${pointer.show} -> ${compact(Schema.normalize(schema))}" }
    .mkString("\n")
}


object FlatSchema {

  case class Changed(what: String, from: Schema.Primitive, to: Schema)
  case class Diff(added: (String, Schema), removed: List[String], changed: Changed)

  def build(schema: Schema): FlatSchema =
    Schema.traverse(schema, FlatSchema.save).runS(FlatSchema.empty).value

  /** Check if `current` JSON Pointer has all parent elements also required */
  /** Redshift-specific */
  // TODO: type object with properties can be primitive if properties are empty
  def isLeaf(schema: Schema): Boolean = {
    val isNested = schema.withType(CommonProperties.Type.Object) && schema.properties.isDefined
    isHeterogeneousUnion(schema) || !isNested
  }

  /** This property shouldn't have been added (FlatSchemaSpec.e4) */
  def shouldBeIgnored(pointer: SchemaPointer, flatSchema: FlatSchema): Boolean =
    pointer.value.exists {
      case Pointer.Cursor.DownProperty(Pointer.SchemaProperty.Items) => true
      case Pointer.Cursor.DownProperty(Pointer.SchemaProperty.PatternProperties) => true
      case Pointer.Cursor.DownProperty(Pointer.SchemaProperty.OneOf) => true
      case _ => false
    } || (pointer.value.lastOption match {
      case Some(Pointer.Cursor.DownProperty(Pointer.SchemaProperty.OneOf)) =>
        true
      case _ => false
    }) || flatSchema.checkUnionSubSchema(pointer)

  def getRequired(cur: SchemaPointer, schema: Schema): Set[SchemaPointer] =
    schema
      .required.map(_.value.toSet)
      .getOrElse(Set.empty)
      .map(prop => cur.downProperty(Pointer.SchemaProperty.Properties).downField(prop))

  val empty = FlatSchema(Set.empty, Set.empty, Set.empty)

  def save(pointer: SchemaPointer, schema: Schema): State[FlatSchema, Unit] =
    State.modify[FlatSchema] { flatSchema =>
      if (shouldBeIgnored(pointer, flatSchema))
        flatSchema
      else if (isLeaf(schema))
        flatSchema
          .withRequired(pointer, schema)
          .withLeaf(pointer, schema)
      else {
        flatSchema
          .withRequired(pointer, schema)
          .withParent(pointer, schema)
      }
    }

  def order(subschemas: SubSchemas): List[(Pointer.SchemaPointer, Schema)] =
    subschemas.toList.sortBy { case (pointer, schema) =>
      (schema.canBeNull, getName(pointer))
    }

  /**
    * Build subschemas which are ordered according to nullness of field,
    * name of field and which version field is added
    * @param source List of ordered schemas to create ordered subschemas
    * @return subschemas which are ordered according to criterias specified
    *         above
    */
  def extractProperties(source: SchemaList): Properties =
    source match {
      case s: SchemaList.Single =>
        val origin = build(s.schema.schema)
        order(origin.subschemas)
      case s: SchemaList.Full =>
        val origin = build(s.schemas.head.schema)
        val originColumns = order(origin.subschemas)
        val addedColumns = Migration.fromSegment(s.toSegment).diff.added
        originColumns ++ addedColumns
    }

  /** Get normalized name */
  def getName(jsonPointer: Pointer.SchemaPointer): String =
    jsonPointer.forData.path.map(StringUtils.snakeCase).mkString(".")

  /** Check if schema contains `oneOf` with different types */
  private[schemaddl] def isHeterogeneousUnion(schema: Schema): Boolean = {
    val oneOfCheck = schema.oneOf match {
      case Some(oneOf) =>
        val types = oneOf.value.map(_.`type`).unite.flatMap {
          case CommonProperties.Type.Union(set) => set.toList
          case singular => List(singular)
        }
        types.distinct .filterNot(_ == CommonProperties.Type.Null) .length > 1
      case None => false
    }
    val unionTypeCheck = schema.`type`.forall(t => t.isUnion)
    oneOfCheck || unionTypeCheck
  }
}