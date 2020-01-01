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

package laika.api

import laika.api.builder.{OperationConfig, RendererBuilder, TwoPhaseRendererBuilder}
import laika.config.ConfigError
import laika.ast.Path.Root
import laika.ast._
import laika.factory.{RenderContext, RenderFormat, TwoPhaseRenderFormat}
import laika.rewrite.TemplateRewriter

/** Performs a render operation from a document AST to a target format
  * as a string. The document AST may be obtained by a preceding parse 
  * operation or constructed programmatically. 
  *
  * In cases where a parse operation should precede immediately, it is more 
  * convenient to use a [[laika.api.Transformer]] instead which 
  * combines a parse and a render operation directly.
  *
  * Example for rendering HTML:
  *
  * {{{
  *  val doc: Document = ???
  *  
  *  val res: String = Renderer
  *    .of(HTML)
  *    .build
  *    .render(doc)
  * }}}
  *
  * This is a pure API that does not perform any side-effects.
  * For additional options like File and Stream I/O, templating 
  * or parallel processing, use the corresponding builders in 
  * the laika-io module.
  *
  * @author Jens Halm
  */
abstract class Renderer (val config: OperationConfig) {

  type Formatter

  def format: RenderFormat[Formatter]

  private lazy val theme = config.themeFor(format)

  private lazy val renderFunction: (Formatter, Element) => String = (fmt, element) =>
    theme.customRenderer.applyOrElse[(Formatter, Element), String]((fmt, element), {
      case (f, e) => format.defaultRenderer(f, e)
    })


  /** Renders the specified document as a String.
    */
  def render (doc: Document): String = render(doc.content, doc.path, theme.defaultStyles)

  /** Renders the specified document as a String, using the given styles.
    * 
    * Currently only PDF/XSL-FO output processes styles, all other formats
    * will ignore them.
    */
  def render (doc: Document, styles: StyleDeclarationSet): String = render(doc.content, doc.path, styles)

  /** Renders the specified element as a String.
    */
  def render (element: Element): String = render(element, Root, theme.defaultStyles)

  /** Renders the specified element as a String.
    * 
    * The provided (virtual) path may be used by renderers for cross-linking between
    * documents.
    */
  def render (element: Element, path: Path): String = render(element, path, theme.defaultStyles)

  /** Renders the specified element as a String, using the given styles.
    * 
    * Currently only PDF/XSL-FO output processes styles, all other formats
    * will ignore them.
    *
    * The provided (virtual) path may be used by renderers for cross-linking between
    * documents.
    */
  def render (element: Element, path: Path, styles: StyleDeclarationSet): String = {

    val renderContext = RenderContext(renderFunction, element, styles, path, config)

    val formatter = format.formatterFactory(renderContext)

    renderFunction(formatter, element)
  }

  /** Applies the theme that has been configured for this renderer
    * to the specified document tree. 
    * 
    * This is a hook used by renderers and transformers that process
    * entire trees of documents. It is usually not invoked by 
    * application code directly.
    */
  def applyTheme (root: DocumentTreeRoot): Either[ConfigError, DocumentTreeRoot] = {
    val styles = theme.defaultStyles ++ root.styles(format.fileSuffix)
    
    val treeWithTpl: DocumentTree = root.tree.getDefaultTemplate(format.fileSuffix).fold(
      root.tree.withDefaultTemplate(theme.defaultTemplateOrFallback, format.fileSuffix)
    )(_ => root.tree)
    
    val preparedRoot = root.copy(tree = treeWithTpl, styles = root.styles + (format.fileSuffix -> styles))
    
    TemplateRewriter.applyTemplates(preparedRoot, format.fileSuffix)
  }

  /** Returns the template to use for the specified document,
    * either defined in the tree itself, or if missing obtained
    * from the configured theme of this renderer.
    */
  def templateFor (root: DocumentTreeRoot): TemplateRoot = 
    root.tree.getDefaultTemplate(format.fileSuffix).fold(theme.defaultTemplateOrFallback)(_.content)

}

/** Entry point for building a Renderer instance.
  *
  * @author Jens Halm
  */
object Renderer {

  /** Returns a new builder instance for the specified render format.
    * 
    * The format is usually an object provided by the library
    * or a plugin that is capable of producing a specific output format like HTML. 
    */
  def of [FMT] (format: RenderFormat[FMT]): RendererBuilder[FMT] =
    new RendererBuilder[FMT](format, OperationConfig.default)

  /** Returns a new builder instance for the specified two-phase render format.
    * 
    * The format is usually an object provided by the library
    * or a plugin that is capable of producing a specific output format like EPUB or PDF.
    * 
    * While the builder API for two-phase renderers is defined as part of the laika-core module, the concrete
    * implementations of this renderer type that this library provides (EPUB and PDF) 
    * reside in sub-modules as they require the functionality of the laika-io module.
    */
  def of [FMT, PP] (format: TwoPhaseRenderFormat[FMT, PP]): TwoPhaseRendererBuilder[FMT, PP] =
    new TwoPhaseRendererBuilder[FMT, PP](format, OperationConfig.default)

}
