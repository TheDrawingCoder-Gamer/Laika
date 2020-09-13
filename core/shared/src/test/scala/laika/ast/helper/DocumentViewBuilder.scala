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

package laika.ast.helper

import laika.ast._
import laika.config.Config
import laika.rst.ast.CustomizedTextRole

/* Provides a view of DocumentTree structures that allows for formatted AST rendering (for debugging purposes).
 */
object DocumentViewBuilder {

  
  trait View extends Element {
    type Self <: View
    val options: Options = NoOpt
    def withOptions(options: Options): Self = this.asInstanceOf[Self]
  }
  
  trait ViewContainer extends ElementContainer[View] with View
  

  case class RootView (content: Seq[RootContent]) extends ViewContainer
  
  
  trait RootContent extends View
  
  case class TreeView (path: Path, content: Seq[TreeContent], config: Config = Config.empty) extends ViewContainer with RootContent {
    def asRoot: RootView = RootView(Seq(this))
  }

  case class CoverDocument (doc: DocumentView) extends RootContent

  case class StyleSheets (content: Map[String, StyleDeclarationSet]) extends RootContent

  case class StaticDocuments (content: Seq[Path]) extends RootContent
  
  
  trait TreeContent extends View
  
  case class TitleDocument (doc: DocumentView) extends TreeContent
  
  case class Documents (content: Seq[DocumentView]) extends TreeContent with ViewContainer
  
  case class TemplateDocuments (content: Seq[TemplateView]) extends TreeContent with ViewContainer
  
  case class Subtrees (content: Seq[TreeView]) extends TreeContent with ViewContainer
  
  case class DocumentView (path: Path, content: Seq[DocumentContent]) extends ViewContainer

  case class TemplateView (path: Path, content: TemplateRoot) extends View
  
  
  trait DocumentContent extends View
  
  case class Fragments (content: Seq[Fragment]) extends DocumentContent with ViewContainer
  
  case class Fragment (name: String, content: Element) extends View
  
  case class Content (content: Seq[Block]) extends DocumentContent with BlockContainer {
    override type Self = Content
    def withContent (newContent: Seq[Block]): Content = copy(content = newContent)
  }
  
  case class Title (content: Seq[Span]) extends DocumentContent with SpanContainer {
    override type Self = Title
    def withContent (newContent: Seq[Span]): Title = copy(content = newContent)
  }
  
  case class Sections (content: Seq[SectionInfo]) extends DocumentContent with ElementContainer[SectionInfo]

  def viewOf (root: DocumentTreeRoot): RootView = {
    val coverDocument = root.coverDocument.map(doc => CoverDocument(viewOf(doc))).toSeq
    val styles = if (root.styles.nonEmpty) Seq(StyleSheets(root.styles)) else Nil
    val static = if (root.staticDocuments.nonEmpty) Seq(StaticDocuments(root.staticDocuments)) else Nil
    val tree = if (root.allDocuments.nonEmpty || root.tree.templates.nonEmpty) Seq(viewOf(root.tree)) else Nil
    RootView(coverDocument ++ styles ++ static ++ tree)
  }
  
  def viewOf (tree: DocumentTree, includeConfig: Boolean = true): TreeView = {
    val titleDocument = tree.titleDocument.map(doc => TitleDocument(viewOf(doc))).toSeq
    val content = (
      Documents(tree.content.collect{ case doc: Document => doc } map viewOf) ::
      TemplateDocuments(tree.templates map viewOf) ::
      Subtrees(tree.content.collect{case tree: DocumentTree => tree} map (t => viewOf(t, includeConfig))) :: 
      List[TreeContent]()) filterNot { case c: ViewContainer => c.content.isEmpty }
    TreeView(tree.path, titleDocument ++ content, if (includeConfig) tree.config else Config.empty)
  }
  
  def viewOf (doc: Document): DocumentView = {
    def filterTitle (title: Option[SpanSequence]): Seq[Span] = title match {
      case Some(SpanSequence(Seq(Text("",_), _), _)) => Nil
      case Some(other) => other.content
      case None => Nil
    }
    val content = (
      Content(doc.content.rewriteBlocks{ case CustomizedTextRole(_,_,_) => Remove }.content) :: 
      Fragments(viewOf(doc.fragments)) :: 
      Title(filterTitle(doc.title)) ::
      Sections(doc.sections) ::
      List[DocumentContent]()) filterNot { case c: ElementContainer[_] => c.content.isEmpty }
    DocumentView(doc.path, content)
  }
  
  def viewOf (fragments: Map[String, Element]): Seq[Fragment] = 
    (fragments map { case (name, content) => Fragment(name, content) }).toSeq
  
  def viewOf (doc: TemplateDocument): TemplateView = TemplateView(doc.path, doc.content)
  
}
