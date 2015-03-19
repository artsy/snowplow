/*
 * Copyright (c) 2013-2014 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich
package kinesis
package sources

// Amazon
import com.amazonaws.auth._

// Scalaz
import scalaz.{Sink => _, _}
import Scalaz._

// Snowplow
import sinks._
import com.snowplowanalytics.maxmind.geoip.IpGeo
import common.outputs.CanonicalOutput
import common.inputs.ThriftLoader
import common.MaybeCanonicalInput
import common.outputs.CanonicalOutput
import common.enrichments.EnrichmentManager
import common.enrichments.PrivacyEnrichments.AnonOctets
import org.slf4j.LoggerFactory

/**
 * Abstract base for the different sources
 * we support.
 */
abstract class AbstractSource(config: KinesisEnrichConfig) {
  private lazy val log = LoggerFactory.getLogger(getClass())
  import log.{error, debug, info, trace}

  /**
   * Never-ending processing loop over source stream.
   * implement in child class
   */
  def run

  // KinesisSource references this provider, so we need this default value
  var kinesisProvider: AWSCredentialsProvider = _

  /**
   * Fields in our CanonicalOutput which are discarded for legacy
   * Redshift space reasons
   */
  private val DiscardedFields = Array("page_url", "page_referrer")

  // Initialize the sink to output enriched events to.
  protected val sink: Option[ISink] = config.sink match {
    case Sink.Kinesis => {
      kinesisProvider = createKinesisProvider
      new KinesisSink(kinesisProvider, config).some
    }
    case Sink.Stdouterr => new StdouterrSink().some
    case Sink.Test => None
  }

  private lazy val ipGeo = new IpGeo(
    dbFile = config.maxmindFile,
    memCache = false,
    lruCache = 20000
  )
  // Iterate through an enriched CanonicalOutput object and tab separate
  // the fields to a string.
  // TODO this class should wrap the output in a type, so that we can identify
  // what kind of string this is
  def tabSeparateCanonicalOutput(output: CanonicalOutput): String = {
    output.getClass.getDeclaredFields
    .filter { field =>
      !DiscardedFields.contains(field.getName)
    }
    .map{ field =>
      field.setAccessible(true)
      Option(field.get(output)).getOrElse("")
    }.mkString("\t")
  }

  // Helper method to enrich an event.
  // TODO: this is a slightly odd design: it's a pure function if our
  // our sink is Test, but it's an impure function (with
  // storeOutput side effect) for the other sinks. We should
  // break this into a pure function with an impure wrapper.
  def enrichEvent(binaryData: Array[Byte]): Array[Option[String]] = {
    // validate binaryData, is it mapable to Canonical Output? if so store
    // in the canonical storage
    val canonicalInput = ThriftLoader.toCanonicalInput(binaryData)

    canonicalInput.toValidationNel match {

      case Failure(f)        => Array(None)
        // TODO: https://github.com/snowplow/snowplow/issues/463
      case Success(None)     => Array(None) // Do nothing
      case Success(Some(ci)) => {
        val anonOctets =
          if (!config.anonIpEnabled || config.anonOctets == 0) {
            AnonOctets.None
          } else {
            AnonOctets(config.anonOctets)
          }
        // TODO: this value should be a particular kind of a type depending
        // on what kind of enrichment ends up happening
        val canonicalOutputs = EnrichmentManager.enrichEvent(
          ipGeo,
          s"kinesis-${generated.Settings.version}",
          anonOctets,
          ci
        )

        // the case statement should use the type information to pass the concept
        // of which stream to put the event on
        canonicalOutputs.map ( canonicalOutput =>
          canonicalOutput.toValidationNel match {
            case Success(co) =>
              val ts = tabSeparateCanonicalOutput(co)
              for (s <- sink) {
                val stream = if (co.se_category == "impression") "impression" else "canonical";
                // TODO: pull this side effect into parent function
                s.storeOutput(ts, co.user_ipaddress, stream) // ts should have a type that helps the KinesisSink know what stream to output this to
                info(s"storeOutput")
              }
              Some(ts)
            case Failure(f)  => None
              // TODO: https://github.com/snowplow/snowplow/issues/463
          }
        )
      }
    }
  }

  // Initialize a Kinesis provider with the given credentials.
  private def createKinesisProvider(): AWSCredentialsProvider =  {
    val a = config.accessKey
    val s = config.secretKey
    if (isCpf(a) && isCpf(s)) {
        new ClasspathPropertiesFileCredentialsProvider()
    } else if (isCpf(a) || isCpf(s)) {
      throw new RuntimeException(
        "access-key and secret-key must both be set to 'cpf', or neither"
      )
    } else {
      new BasicAWSCredentialsProvider(
        new BasicAWSCredentials(a, s)
      )
    }
  }

  // http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/ClasspathPropertiesFileCredentialsProvider.html
  private def isCpf(key: String): Boolean = (key == "cpf")

  // Wrap BasicAWSCredential objects.
  class BasicAWSCredentialsProvider(basic: BasicAWSCredentials) extends
      AWSCredentialsProvider{
    @Override def getCredentials: AWSCredentials = basic
    @Override def refresh = {}
  }
}
