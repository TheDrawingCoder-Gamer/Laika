/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.parse.hocon

import laika.ast.Path
import laika.collection.TransitionalCollectionOps._
import laika.config.{ASTValue, ArrayValue, Config, ConfigError, ConfigResolverError, ConfigValue, Field, NullValue, ObjectValue, SimpleConfigValue, StringValue}

import scala.collection.mutable

/**
  * @author Jens Halm
  */
object ConfigResolver {

  def resolve(root: ObjectBuilderValue, fallback: Config): Either[ConfigError, ObjectValue] = {
    
    val rootExpanded = mergeObjects(expandPaths(root))
    
    def renderPath(p: Path): String = p.components.mkString(".")
    
    println(s"resolving root: $rootExpanded")
    
    val activeFields   = mutable.Set.empty[Path]
    val resolvedFields = mutable.Map.empty[Path, ConfigValue]
    val startedObjects = mutable.Map.empty[Path, ObjectBuilderValue] // may be in progress or resolved
    val invalidPaths   = mutable.Map.empty[Path, String]
    
    def resolvedValue(path: Path): Option[ConfigValue] = resolvedFields.get(path)
    
    def deepMerge(o1: ObjectValue, o2: ObjectValue): ObjectValue =  {
      val resolvedFields = (o1.values ++ o2.values).groupBy(_.key).mapValuesStrict(_.map(_.value)).toSeq.map {
        case (name, values) => Field(name, values.reduce(merge))
      }
      ObjectValue(resolvedFields.sortBy(_.key))
    }

    def resolveValue(path: Path)(value: ConfigBuilderValue): Option[ConfigValue] = value match {
      case o: ObjectBuilderValue   => Some(resolveObject(o, path))
      case a: ArrayBuilderValue    => Some(ArrayValue(a.values.flatMap(resolveValue(path)))) // TODO - adjust path?
      case r: ResolvedBuilderValue => Some(r.value)
      case c: ConcatValue          => c.allParts.flatMap(resolveConcatPart(path)).reduceOption(concat(path))
      case m: MergedValue          => resolveMergedValue(path: Path)(m.values.reverse)
      case SelfReference           => None
      case SubstitutionValue(ref, optional) =>
        println(s"resolve ref '${ref.toString}'")
        resolvedValue(ref).orElse(lookahead(ref)).orElse {
          if (!optional) invalidPaths += ((path, s"Missing required reference: '${renderPath(ref)}'"))
          None
        }
    }
    
    def resolveMergedValue(path: Path)(values: Seq[ConfigBuilderValue]): Option[ConfigValue] = {
      
      def loop(values: Seq[ConfigBuilderValue]): Option[ConfigValue] = (resolveValue(path)(values.head), values.tail) match {
        case (Some(ov: ObjectValue), Nil)  => Some(ov)
        case (Some(ov: ObjectValue), rest) => loop(rest) match {
          case Some(o2: ObjectValue) => Some(merge(o2, ov))
          case _ => Some(ov)
        }
        case (Some(other), _) => Some(other)
        case (None, Nil)      => None
        case (None, rest)     => loop(rest)
      }
      
      loop(values)
    }
    
    def lookahead(path: Path): Option[ConfigValue] = {
      
      def resolvedParent(current: Path): Option[(ObjectBuilderValue, Path)] = {
        if (current == Path.Root) Some((rootExpanded, current))
        else {
          val matching = startedObjects.toSeq.filter(o => current.isSubPath(o._1))
          val sorted = matching.sortBy(_._1.components.length)
          println(s"matching active objects for path $current: ${sorted.map(_._1.toString).mkString(" ")}")
          sorted.lastOption.fold(resolvedParent(current.parent)) {
            case (commonPath, obv) => Some((obv, Path(Path.Root, current.components.take(commonPath.components.size + 1))))
          }
        }
      } 
      
      if (activeFields.contains(path)) {
        invalidPaths += ((path, s"Circular Reference involving path '${renderPath(path)}'"))
        Some(NullValue)
      } else {
        resolvedParent(path).flatMap { case (obj, fieldPath) =>
          println(s"lookahead from '${fieldPath.toString}'")
          println(s"keys before lookahead: ${resolvedFields.keySet.map(_.toString).mkString(" ")}")
          println(s"keys in selected parent: ${obj.values.map(_.key.toString).mkString(" ")}")
          obj.values.find(_.key == fieldPath).map(_.value).foreach(resolveField(fieldPath, _, obj)) // TODO - handle None
          println(s"keys after lookahead: ${resolvedFields.keySet.map(_.toString).mkString(" ")}")
          val res = resolvedValue(path).orElse(fallback.get[ConfigValue](path).toOption)
          println(s"success? ${res.isDefined}")
          res
        }
      }
    }
    
    def resolveConcatPart(path: Path)(part: ConcatPart): Option[ConfigValue] = part.value match {
      case SelfReference => None
      case other => resolveValue(path)(other) match {
        case Some(simpleValue: SimpleConfigValue) => Some(StringValue(part.whitespace + simpleValue.render))
        case Some(_: ASTValue)                    => None
        case other                                => other
      }
    } 

    def concat(path: Path)(v1: ConfigValue, v2: ConfigValue): ConfigValue = {
      (v1, v2) match {
        case (o1: ObjectValue, o2: ObjectValue) => deepMerge(o1, o2)
        case (a1: ArrayValue, a2: ArrayValue) => ArrayValue(a1.values ++ a2.values)
        case (s1: StringValue, s2: StringValue) => StringValue(s1.value ++ s2.value)
        case (NullValue, a2: ArrayValue) => a2
        case _ => 
          invalidPaths += ((path, s"Invalid concatenation of values. It must contain either only objects, only arrays or only simple values"))
          NullValue
      }
    }
    
    def merge(v1: ConfigValue, v2: ConfigValue): ConfigValue = {
      (v1, v2) match {
        case (o1: ObjectValue, o2: ObjectValue) => deepMerge(o1, o2)
        case (_, c2) => c2
      }
    }
    
    def resolveField(path: Path, value: ConfigBuilderValue, parent: ObjectBuilderValue): Option[ConfigValue] = {
      resolvedValue(path).orElse {
        println(s"resolve field '${path.toString}'")
        activeFields += path
        val res = resolveValue(path)(value)
        activeFields -= path
        res.foreach { resolved =>
          resolvedFields += ((path, resolved))
        }
        res
      }
    }
    
    def resolveObject(obj: ObjectBuilderValue, path: Path): ObjectValue = {
      startedObjects += ((path, obj))
      println(s"resolve obj with keys: ${obj.values.map(_.key.toString).mkString(" ")}")
      val resolvedFields = obj.values.flatMap { field =>
        resolveField(field.key, field.value, obj).map(Field(field.key.name, _))
      }
      ObjectValue(resolvedFields.sortBy(_.key))
    }
    
    val res = resolveObject(rootExpanded, Path.Root)
    
    if (invalidPaths.nonEmpty) Left(ConfigResolverError(
      s"One or more errors resolving configuration: ${invalidPaths.map{ case (key, msg) => s"'${renderPath(key)}': $msg" }.mkString(", ")}"
    ))
    else Right(res)
  }
  
  def mergeObjects(obj: ObjectBuilderValue): ObjectBuilderValue = {

    def resolveSelfReference(path: Path, value: ConcatValue, parent: ConfigBuilderValue): ConfigBuilderValue = {
      def resolve (value: ConfigBuilderValue): ConfigBuilderValue = value match {
        case SelfReference => parent
        case SubstitutionValue(ref, _) if ref == path => parent
        case other => other
      }
      val resolved = ConcatValue(resolve(value.first), value.rest.map(p => p.copy(value = resolve(p.value))))
      if (resolved == value) MergedValue(Seq(parent, value)) else resolved
    }
    
    def mergeValues(path: Path)(cbv1: ConfigBuilderValue, cbv2: ConfigBuilderValue): ConfigBuilderValue = (cbv1, cbv2) match {
      case (o1: ObjectBuilderValue, o2: ObjectBuilderValue) => mergeObjects(ObjectBuilderValue(o1.values ++ o2.values))
      case (v1, SelfReference) => v1
      case (v1, SubstitutionValue(ref, _)) if ref == path => v1
      case (_, r2: ResolvedBuilderValue) => r2
      case (_, a2: ArrayBuilderValue) => a2
      case (_, o2: ObjectBuilderValue) => o2
      case (v1, c2: ConcatValue) => resolveSelfReference(path, c2, v1)
      case (MergedValue(vs), v2) => MergedValue(vs :+ v2) 
      case (v1, v2) => MergedValue(Seq(v1, v2))
    }
    
    val mergedFields = obj.values.groupBy(_.key).mapValuesStrict(_.map(_.value)).toSeq.map {
      case (path, values) => BuilderField(path.name, values.reduce(mergeValues(path)))
    }
    ObjectBuilderValue(mergedFields)
  }
   
  /** Expands all flattened path expressions to nested objects.
    * 
    * ```
    * { a.b.c = 7 }
    * ```
    * 
    * will become
    * 
    * ```
    * { a = { b = { c = 7 }}}
    * ```
    */
  def expandPaths(obj: ObjectBuilderValue, path: Path = Path.Root): ObjectBuilderValue = {
    
    def expandValue(value: ConfigBuilderValue, child: Path): ConfigBuilderValue = value match {
      case o: ObjectBuilderValue => expandPaths(o, child)
      case a: ArrayBuilderValue => 
        val expandedElements = a.values.zipWithIndex.map { case (element, index) =>
          expandValue(element, child / index.toString)
        }
        ArrayBuilderValue(expandedElements)
      case c: ConcatValue => c.copy(
        first = expandValue(c.first, child),
        rest = c.rest.map(part => part.copy(value = expandValue(part.value, child)))
      )
      case other => other
    }
    
    val expandedFields = obj.values.map { field =>
      //println(s"expand key ${field.key.toString}")
      field.key.components match {
        case name :: Nil => 
          field.copy(
            key = path / name, 
            value = expandValue(field.value, path / name)
          )
        case name :: rest =>
          field.copy(
            key = path / name, 
            value = expandPaths(ObjectBuilderValue(Seq(BuilderField(Path(rest), field.value))), path / name)
          )
        case Nil =>
          field.copy(
            key = Path.Root,
            value = expandValue(field.value, Path.Root) // TODO - 0.13 - should never get here
          )
      }
    }
    //println(s"expanded keys: ${expandedFields.map(_.key.toString).mkString(" ")}")
    obj.copy(values = expandedFields)
  }
  
}
