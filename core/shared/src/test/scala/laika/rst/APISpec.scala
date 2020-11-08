/*
 * Copyright 2012-2020 the original author or authors.
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

package laika.rst

import cats.implicits._
import laika.api._
import laika.ast._
import laika.ast.sample.ParagraphCompanionShortcuts
import laika.directive.{Blocks, DirectiveRegistry, Spans}
import laika.format.ReStructuredText
import laika.rst.ext.Directives.Parts._
import laika.rst.ext.Directives._
import laika.rst.ext.ExtensionProvider
import laika.rst.ext.TextRoles._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class APISpec extends AnyFlatSpec 
                 with Matchers
                 with ParagraphCompanionShortcuts {
  
  
  "The API" should "support registration of block directives" in {
    val directives = List(
      BlockDirective("oneArg")(argument() map p),
      BlockDirective("twoArgs")((argument() ~ argument()).map { case arg1 ~ arg2 => p(arg1+arg2) })
    )
    val input = """.. oneArg:: arg
      |
      |.. twoArgs:: arg arg""".stripMargin
    MarkupParser.of(ReStructuredText).using(ExtensionProvider.forExtensions(blocks = directives)).build.parse(input)
      .toOption.get.content should be (RootElement(p("arg"),p("argarg")))
  }
  
  it should "support registration of span directives" in {
    val directives = List(
      SpanDirective("oneArg")(argument() map (Text(_))),
      SpanDirective("twoArgs")((argument() ~ argument()).map { case arg1 ~ arg2 => Text(arg1+arg2) })
    )
    val input = """foo |one| foo |two|
      |
      |.. |one| oneArg:: arg
      |
      |.. |two| twoArgs:: arg arg""".stripMargin
    MarkupParser.of(ReStructuredText).using(ExtensionProvider.forExtensions(spans = directives)).build.parse(input)
      .toOption.get.content should be (RootElement
        (p(Text("foo arg foo argarg"))))
  }
  
  it should "support registration of text roles" in {
    import laika.rst.ext.TextRoles.{Parts => P}
    val roles = List(
      TextRole("oneArg", "foo1")(P.field("name")) { (res,text) =>
       Text(res+text)
      },
      TextRole("twoArgs", "foo2")
        ((P.field("name1") ~ P.field("name2")).map { case arg1 ~ arg2 => arg1+arg2 }) 
        { (res,text) => Text(res+text) }
    )
    val input = """foo `one`:one: foo :two:`two`
      |
      |.. role::one(oneArg)
      | :name: val
      |
      |.. role::two(twoArgs)
      | :name1: val1
      | :name2: val2""".stripMargin
    MarkupParser.of(ReStructuredText).using(ExtensionProvider.forExtensions(roles = roles)).build.parse(input)
      .toOption.get.content should be (RootElement(p(Text("foo valone foo val1val2two"))))
  }
  
  trait BlockDirectives {
    import Blocks.dsl._

    object TestDirectives extends DirectiveRegistry {

      val blockDirectives: List[Blocks.Directive] = List(
        Blocks.create("oneArg")(attribute(0).as[String] map p),
        Blocks.create("twoArgs") {
          (attribute(0).as[String], attribute("name").as[String].widen).mapN { (arg1, arg2) => p(arg1 + arg2) }
        }
      )

      val spanDirectives = Seq()
      val templateDirectives = Seq()
      val linkDirectives = Seq()
    }
  }
  
  trait SpanDirectives {
    import Spans.dsl._

    object TestDirectives extends DirectiveRegistry {

      val spanDirectives: List[Spans.Directive] = List(
        Spans.create("oneArg")(attribute(0).as[String].map(Text(_))),
        Spans.create("twoArgs") {
          (attribute(0).as[String], attribute("name").as[String].widen).mapN { 
            (arg1, arg2) => Text(arg1+arg2) 
          }
        }
      )

      val blockDirectives = Seq()
      val templateDirectives = Seq()
      val linkDirectives = Seq()
    }
  }
  
  it should "support the registration of Laika block directives" in {
    new BlockDirectives {
      val input = """@:oneArg(arg)
        |
        |@:twoArgs(arg1) { name=arg2 }""".stripMargin
      MarkupParser.of(ReStructuredText).using(TestDirectives).build.parse(input).toOption.get.content should be (RootElement(p("arg"),p("arg1arg2")))
    }
  }

  it should "ignore the registration of Laika block directives when run in strict mode" in {
    new BlockDirectives {
      val input = """@:oneArg(arg)
        |
        |@:twoArgs(arg1) { name=arg2 }""".stripMargin
      MarkupParser.of(ReStructuredText).using(TestDirectives).strict.build.parse(input).toOption.get.content should be (RootElement(p("@:oneArg(arg)"),p("@:twoArgs(arg1) { name=arg2 }")))
    }
  }
  
  it should "support the registration of Laika span directives" in {
    new SpanDirectives {
      val input = """one @:oneArg(arg) two @:twoArgs(arg1) { name=arg2 } three"""
      MarkupParser.of(ReStructuredText).using(TestDirectives).build.parse(input).toOption.get.content should be (RootElement(p("one arg two arg1arg2 three")))
    }
  }

  it should "ignore the registration of Laika span directives when run in strict mode" in {
    new SpanDirectives {
      val input = """one @:oneArg(arg) two @:twoArgs(arg1) { name=arg2 } three"""
      MarkupParser.of(ReStructuredText).using(TestDirectives).strict.build.parse(input).toOption.get.content should be (RootElement(p("one @:oneArg(arg) two @:twoArgs(arg1) { name=arg2 } three")))
    }
  }
  
  it should "pre-process tabs" in {
    val input = " Line1\n\tLine2\n\tLine3"
    val list = DefinitionList(
      DefinitionListItem("Line1", p("Line2\nLine3"))
    )
    MarkupParser.of(ReStructuredText).build.parse(input).toOption.get.content should be (RootElement(QuotedBlock(list)))
  }
  

}
