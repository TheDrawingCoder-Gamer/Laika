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

package laika.time

import java.util.Date

/** A little abstraction that isolates aspects of parsing and formatting
  * dates from the underlying Date API which may differ between JVM
  * and Scala.js applications.
  * 
  * The design is very minimal as date handling is not a core aspect of
  * Laika. For that reason it uses `java.util.Date` as the lowest common
  * denominator. Application code should convert it to the most convenient
  * type for the platform in use, e.g. with `toInstant` on the JVM or
  * with `new js.Date(date.getTime().toDouble)` in Scala.js.
  * 
  * There was a deliberate choice not to depend on any of the available
  * libraries that port the `java.time` API to Scala, as this would
  * be to heavyweight, in particular for Scala.js.
  * 
  * @author Jens Halm
  */
trait PlatformDateFormat {

  /** The platform-dependent type representing dates. */
  type Type
  
  private[laika] def now: Type // impure impl only used by reStructuredText date directive and not public API 

  /** Parses the specified string either as a date with time zone,
    * or a local date time, or just a date.
    * 
    * In case of a date time with time zone, ISO 8601 is supported 
    * (allowing either `Z` for UTC or `+`/`-` followed by a time offset 
    * with or without colon, e.g. `+01:30` or `+0130`.
    * 
    * In case of the time zone info missing, local date time is assumed.
    * 
    * Examples: `2011-10-10` or `2011-10-10T14:48:00` or `2011-10-10T14:48:00+0100`
    * are all allowed.
    * 
    * This is also designed to be somewhat aligned to `Date.parse` in JavaScript.
    */
  def parse (dateString: String): Either[String, Type]

  /** Formats the specified date with the given pattern.
    * 
    * The result will be a `Left` in case the pattern is invalid.
    */
  private[laika] def format (date: Type, pattern: String): Either[String, String]
  
}

object PlatformDateFormat extends PlatformDateFormat {
  
  /*
  This indirection is not strictly necessary, but reduces good-code-red in IDEs,
  which are struggling to deal with classes in the shared folder pointing to classes
  which are implemented twice (with the same signatures) in jvm and js projects.
   */
  
  type Type = PlatformDateFormatImpl.Type

  private[laika] def now: Type = PlatformDateFormatImpl.now

  def parse (dateString: String): Either[String, Type] = 
    PlatformDateFormatImpl.parse(dateString)

  private[laika] def format (date: Type, pattern: String): Either[String, String] = 
    PlatformDateFormatImpl.format(date, pattern)
}
