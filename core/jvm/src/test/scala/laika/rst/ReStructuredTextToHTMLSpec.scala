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

import cats.data.NonEmptySet
import laika.api.Transformer
import laika.api.format.TagFormatter
import laika.ast.Path.Root
import laika.ast.*
import laika.config.LaikaKeys
import laika.file.FileIO
import laika.format.{ HTML, ReStructuredText }
import laika.html.TidyHTML
import munit.FunSuite

import scala.io.Codec

/** @author Jens Halm
  */
class ReStructuredTextToHTMLSpec extends FunSuite {

  implicit val codec: Codec = Codec.UTF8

  /** Uses JTidy to remove cosmetic differences between Laika and reStructuredText output,
    *  plus a few additional, manual cleaning operations for purely cosmetic differences
    *  not covered by JTidy.
    */
  def tidyAndAdjust(html: String): String = {
    val prepared = html
      .replace("\r\n", "\n")
      .replace("\r", "\n")
      .replaceAll("([^\n])</", "$1\n</") // rst often inserts a new line before a closing tag
      .replace("$Revision: 7629 $", "7629")
      .replace(
        "$Date: 2013-03-11 21:01:03 +0000 (Mon, 11 Mar 2013) $",
        "2013-03-11"
      )                                  // RCS field substitution not supported
      .replace("""class="field-list"""", """class="docinfo"""")
      .replace("""class="field-name"""", """class="docinfo-name"""")
      .replace(
        """class="field-body"""",
        """class="docinfo-content""""
      )                                  // docinfo field lists deferred to 0.4

    TidyHTML(prepared)
      .replace("<col></colgroup>", "<col>\n</colgroup>") // fix for JTidy oddity
      .replace(">\n\n<", ">\n<")                         // rst often adds blank lines between tags
      .replace("§§§§", "&mdash;") // JTidy converts it back to utf-8 char otherwise
      .trim
  }

  def transformAndCompare(name: String): Unit = {
    val path  = FileIO.classPathResourcePath("/rstSpec") + "/" + name
    val input = FileIO.readFile(path + ".rst")

    def renderQuotedBlock(fmt: TagFormatter, qb: QuotedBlock): String = {
      val tagName = "blockquote"

      val quotedBlockContainer: BlockContainer =
        if (qb.attribution.isEmpty) qb
        else
          BlockSequence(
            qb.content :+ Paragraph(
              RawContent(NonEmptySet.one("html"), "§§§§") +: qb.attribution,
              Style.attribution
            )
          )

      quotedBlockContainer.content match {
        case Seq(ss: SpanSequence)      => fmt.element(tagName, ss.withOptions(qb.options))
        case Seq(Paragraph(spans, opt)) =>
          fmt.element(tagName, SpanSequence(spans).withOptions(opt))
        case _ => fmt.indentedElement(tagName, quotedBlockContainer.withOptions(qb.options))
      }
    }

    def dropLaikaPrefix(id: String): String = if (id.startsWith("__")) id.drop(5) else id

    /* This set of renderer overrides adjust Laika's generic HTML renderer to the specific output generated by the reference implementation.
       These adjustments are not integrated into the Laika's core as it would be complicated overhead to have different renderers per
       input format, in particular in case where more than one markup format is used as the input.
     */
    val actual = Transformer
      .from(ReStructuredText)
      .to(HTML)
      .rendering {
        case (fmt, i @ InvalidSpan(_, _, Literal(fb, _), _))       =>
          fmt.child(i.copy(fallback = Text(fb)))
        case (fmt, e: Emphasized) if e.hasStyle("title-reference") =>
          fmt.element("cite", e.clearOptions)
        case (fmt, sl @ SpanLink(_, target, title, _))             =>
          target match {
            case ExternalTarget(url) =>
              fmt.element(
                "a",
                sl.withStyles("reference", "external"),
                fmt.optAttributes("href" -> Some(url), "title" -> title) *
              )
            case it: InternalTarget  =>
              val relativePath = it.relativeTo(fmt.path).relativePath
              // rst makes a different kind of distinction between external and internal links, so we adjust Laika's renderers just for this test
              if (relativePath.suffix.contains("html") || relativePath.fragment.isEmpty)
                fmt.child(sl.copy(target = ExternalTarget(relativePath.toString)))
              else
                fmt.element(
                  "a",
                  sl.withStyles("reference", "internal"),
                  fmt.optAttributes(
                    "href"  -> Some("#" + relativePath.fragment.get),
                    "title" -> title
                  ) *
                )
          }

        case (fmt, lb: LiteralBlock)             =>
          fmt.withoutIndentation(_.textElement("pre", lb.withStyle("literal-block")))
        case (fmt, l: Literal)                   =>
          fmt.withoutIndentation(_.textElement("tt", l.withStyles("docutils", "literal")))
        case (fmt, FootnoteLink(id, label, opt)) =>
          val text = Text(s"[$label]").withOptions(opt + Styles("footnote-reference"))
          fmt.textElement("a", text, "href" -> ("#" + dropLaikaPrefix(id)))

        case (fmt, f: Footnote) if f.options.id.exists(_.startsWith("__")) =>
          fmt.child(f.withId(dropLaikaPrefix(f.options.id.get)))
        case (fmt, Section(header, content, opt))                          =>
          val options = opt + Id(header.options.id.getOrElse("")) +
            (if (header.level == 1) Styles("document") else Style.section)
          fmt.indentedElement(
            "div",
            BlockSequence(header +: content).withOptions(options)
          )
        case (fmt, Header(level, (it: InternalLinkTarget) :: rest, opt))   =>
          fmt.childPerLine(
            Seq(it, Header(level, rest, opt))
          ) // move target out of the header content
        case (fmt, h: Header)                                              =>
          fmt.element("h" + (h.level - 1), h.clearOptions) // rst special treatment of first header
        case (fmt, t: Title) => fmt.element("h1", t.clearOptions, "class" -> "title")
        case (fmt, TitledBlock(title, content, opt)) =>
          val element = BlockSequence(Paragraph(title, Styles("admonition-title")) +: content)
            .withOptions(opt)
          fmt.indentedElement("div", element)
        case (fmt, qb: QuotedBlock)                  => renderQuotedBlock(fmt, qb)
        case (fmt, InternalLinkTarget(opt)) => fmt.textElement("span", Text("").withOptions(opt))
        case (_, _: InvalidBlock)           => ""
      }
      .failOnMessages(MessageFilter.None)
      .withConfigValue(LaikaKeys.firstHeaderAsTitle, true)
      .build
      .transform(input, Root / "doc")
      .map(tidyAndAdjust)

    val expected = FileIO.readFile(path + "-tidy.html")

    assertEquals(actual, Right(expected))
  }

  test(
    "transform the reStructuredText specification to HTML equivalent to the output of the reference parser"
  ) {
    transformAndCompare("rst-spec")
  }

}
