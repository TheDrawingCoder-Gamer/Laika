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

package laika.runtime

import java.io.{FileInputStream, FileOutputStream, InputStream, OutputStream}

import cats.effect.{Async, Sync}

/**
  * @author Jens Halm
  */
object CopyRuntime {

  /** Copies all bytes from the specified InputStream to the
    *  OutputStream. Rethrows all Exceptions and does not
    *  close the streams afterwards.
    */
  def copy[F[_]: Sync] (input: InputStream, output: OutputStream): F[Unit] = (input, output) match {
    case (in: FileInputStream, out: FileOutputStream) =>
      Sync[F].delay(in.getChannel.transferTo(0, Integer.MAX_VALUE, out.getChannel))
    case _ =>
      Sync[F].delay {
        val buffer = new Array[Byte](8192)
        Iterator.continually(input.read(buffer))
          .takeWhile(_ != -1)
          .foreach { output.write(buffer, 0 , _) }
      }
  }

  /** Copies all bytes or characters (depending on Input type) 
    *  from the specified Input to the
    *  Output. Rethrows all Exceptions and does not
    *  close the Input or Output afterwards.
    */
  //  def copy (input: BinaryInput, output: BinaryOutput): Unit = {
  //
  //    val sameFile = (input, output) match {
  //      case (a: BinaryFileInput, b: BinaryFileOutput) => a.file == b.file
  //      case _ => false
  //    }
  //
  //    if (!sameFile) {
  //      val inputStream = input match {
  //        case BinaryFileInput(file, _) => new BufferedInputStream(new FileInputStream(file)) // TODO - 0.12 - avoid duplication
  //        case ByteInput(bytes, _)      => new ByteArrayInputStream(bytes)
  //      }
  //      val outputStream = OutputRuntime.asStream(output)
  //      apply(inputStream) { in => apply(outputStream) { out => copy(in, out) } }
  //    }
  //  }
  
}
