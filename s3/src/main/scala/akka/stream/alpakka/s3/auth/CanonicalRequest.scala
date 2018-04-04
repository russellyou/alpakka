/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.s3.auth

import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}

// Documentation: http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
private[alpakka] final case class CanonicalRequest(
    method: String,
    uri: String,
    queryString: String,
    headerString: String,
    signedHeaders: String,
    hashedPayload: String
) {
  def canonicalString: String =
    s"$method\n$uri\n$queryString\n$headerString\n\n$signedHeaders\n$hashedPayload"
}

private[alpakka] object CanonicalRequest {
  def from(request: HttpRequest): CanonicalRequest = {
    val hashedBody =
      request.headers
        .collectFirst { case header if header.is("x-amz-content-sha256") => header.value }
        .getOrElse("")

    CanonicalRequest(
      request.method.value,
      pathEncode(request.uri.path),
      canonicalQueryString(request.uri.query()),
      canonicalHeaderString(request.headers),
      signedHeadersString(request.headers),
      hashedBody
    )
  }

  // https://tools.ietf.org/html/rfc3986#section-2.3
  def isUnreservedCharacter(c: Char): Boolean =
    c.isLetterOrDigit || c == '-' || c == '.' || c == '_' || c == '~'

  // https://tools.ietf.org/html/rfc3986#section-2.2
  // Excludes "/" as it is an exception according to spec.
  val reservedCharacters: String = ":?#[]@!$&'()*+,;="

  def isReservedCharacter(c: Char): Boolean =
    reservedCharacters.contains(c)

  def canonicalQueryString(query: Query): String = {
    def uriEncode(s: String): String = s.flatMap {
      case c if isUnreservedCharacter(c) => c.toString
      case c => "%" + c.toHexString.toUpperCase
    }

    query
      .sortBy { case (name, _) => name }
      .map { case (name, value) => s"${uriEncode(name)}=${uriEncode(value)}" }
      .mkString("&")
  }

  def canonicalHeaderString(headers: Seq[HttpHeader]): String =
    headers
      .groupBy(_.lowercaseName)
      .map {
        case (name, headers) =>
          name -> headers
            .map(header => header.value.replaceAll("\\s+", " ").trim)
            .mkString(",")
      }
      .toList
      .sortBy { case (name, _) => name }
      .map { case (name, value) => s"$name:$value" }
      .mkString("\n")

  def signedHeadersString(headers: Seq[HttpHeader]): String =
    headers.map(_.lowercaseName).distinct.sorted.mkString(";")

  def pathEncode(path: Path): String =
    if (path.isEmpty) "/"
    else {
      path.toString.flatMap {
        case c if isReservedCharacter(c) => "%" + c.toHexString.toUpperCase
        case c => c.toString
      }
    }
}
